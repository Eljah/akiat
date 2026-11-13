package akiat.review;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Manual, time-preserving spectral band attenuation. No learned separation. */
final class EqualizerData {
    static final int SIZE=2048,HOP=512;
    static final class Settings {
        final double low,high,db;
        Settings(double low,double high,double db){this.low=low;this.high=high;this.db=db;}
        void validate(double rate){if(!Double.isFinite(low)||!Double.isFinite(high)||!Double.isFinite(db)||low<0||high<=low||high>rate/2||db>0||db< -60)throw new IllegalArgumentException("Проверьте диапазон частот и подавление от −60 до 0 дБ");}
        Map<String,Object> json(){Map<String,Object> m=new LinkedHashMap<>();m.put("method","manual-stft-band-v1");m.put("low_hz",low);m.put("high_hz",high);m.put("gain_db",db);return m;}
        double gain(double hz){double fade=Math.max(20,(high-low)*.1),weight=hz>=low&&hz<=high?1:hz<low?Math.max(0,1-(low-hz)/fade):Math.max(0,1-(hz-high)/fade);weight=.5-.5*Math.cos(Math.PI*weight);return Math.pow(10,db*weight/20);}
    }
    static String hash(Path path)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    static Map<String,Object> read(Path path)throws Exception{return Json.object(Json.parse(Files.readString(path)));}
    static Path path(Path report,String uid){if(!uid.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid ID");return report.resolve("equalizer").resolve(uid+".json");}
    static Settings settings(Map<String,Object> m){if(!"manual-stft-band-v1".equals(m.get("method")))throw new IllegalArgumentException("Неизвестный эквалайзер");return new Settings(((Number)m.get("low_hz")).doubleValue(),((Number)m.get("high_hz")).doubleValue(),((Number)m.get("gain_db")).doubleValue());}
    static Settings applied(Path report,ReviewData.Item item)throws Exception{Path p=path(report,item.uid);if(!Files.exists(p))return null;Map<String,Object> m=read(p);if(!Boolean.TRUE.equals(m.get("enabled")))return null;if(!item.uid.equals(m.get("uid"))||!hash(item.audio).equals(m.get("source_sha256")))throw new IllegalArgumentException("Исходный WAV для эквалайзера изменился");return settings(m);}
    static void write(Path p,Object value)throws Exception{Files.createDirectories(p.getParent());Path temp=Files.createTempFile(p.getParent(),"eq-",".tmp");try{Files.writeString(temp,Json.write(value));Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    static void apply(Path report,ReviewData.Item item,Settings settings)throws Exception{ReviewData.Sound s=ReviewData.readSound(item.audio);settings.validate(s.format.getSampleRate());Map<String,Object> m=settings.json();m.put("uid",item.uid);m.put("enabled",true);m.put("source_sha256",hash(item.audio));write(path(report,item.uid),m);}
    static void disable(Path report,ReviewData.Item item)throws Exception{Path p=path(report,item.uid);if(Files.exists(p)){Map<String,Object> m=read(p);m.put("enabled",false);write(p,m);}}
    static ReviewData.Sound appliedSound(Path report,ReviewData.Item item,ReviewData.Sound original)throws Exception{Settings s=applied(report,item);return s==null?null:filter(original,s);}
    static double window(int i){return Math.sqrt(.5-.5*Math.cos(2*Math.PI*i/SIZE));}
    static void fft(double[] real,double[] imag,boolean inverse){
        int n=real.length;for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double r=real[i];real[i]=real[j];real[j]=r;r=imag[i];imag[i]=imag[j];imag[j]=r;}}
        for(int len=2;len<=n;len<<=1){double angle=(inverse?2:-2)*Math.PI/len,ur=Math.cos(angle),ui=Math.sin(angle);for(int base=0;base<n;base+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int a=base+j,b=a+len/2;double r=real[b]*wr-imag[b]*wi,v=real[b]*wi+imag[b]*wr;real[b]=real[a]-r;imag[b]=imag[a]-v;real[a]+=r;imag[a]+=v;double next=wr*ur-wi*ui;wi=wr*ui+wi*ur;wr=next;}}}
        if(inverse)for(int i=0;i<n;i++){real[i]/=n;imag[i]/=n;}
    }
    static double sample(ReviewData.Sound s,int frame,int channel){int p=frame*s.format.getFrameSize()+channel*2;return (short)((s.bytes[p]&255)|(s.bytes[p+1]<<8))/32768.;}
    static ReviewData.Sound filter(ReviewData.Sound input,Settings settings){
        settings.validate(input.format.getSampleRate());if(settings.db==0)return input;
        int frames=input.bytes.length/input.format.getFrameSize(),channels=input.format.getChannels();ReviewData.Sound output=new ReviewData.Sound();output.format=input.format;output.duration=input.duration;output.bytes=new byte[input.bytes.length];
        double[] gains=new double[SIZE];for(int i=0;i<SIZE;i++)gains[i]=settings.gain(Math.min(i,SIZE-i)*input.format.getSampleRate()/SIZE);
        for(int channel=0;channel<channels;channel++){
            double[] sum=new double[frames],weight=new double[frames];
            for(int start=-SIZE+HOP;start<frames;start+=HOP){double[] re=new double[SIZE],im=new double[SIZE];for(int i=0;i<SIZE;i++){int frame=start+i;if(frame>=0&&frame<frames)re[i]=sample(input,frame,channel)*window(i);}fft(re,im,false);for(int i=0;i<SIZE;i++){re[i]*=gains[i];im[i]*=gains[i];}fft(re,im,true);
                for(int i=0;i<SIZE;i++){int frame=start+i;if(frame>=0&&frame<frames){double w=window(i);sum[frame]+=re[i]*w;weight[frame]+=w*w;}}}
            for(int i=0;i<frames;i++){int value=(int)Math.max(-32768,Math.min(32767,Math.round(sum[i]/Math.max(1e-12,weight[i])*32768)));int p=i*input.format.getFrameSize()+channel*2;output.bytes[p]=(byte)value;output.bytes[p+1]=(byte)(value>>8);}
        }
        int window=Math.max(1,(int)(input.format.getSampleRate()*.025));output.peaks=new float[(frames+window-1)/window];for(int i=0;i<frames;i++)output.peaks[i/window]=Math.max(output.peaks[i/window],(float)Math.abs(sample(output,i,0)));return output;
    }
    static float[][] spectrum(ReviewData.Sound input,double from,double to){
        int first=(int)Math.round(from*input.format.getSampleRate()),last=Math.min(input.bytes.length/input.format.getFrameSize(),(int)Math.round(to*input.format.getSampleRate()));
        int columns=Math.max(1,Math.min(500,(last-first+HOP-1)/HOP));float[][] values=new float[columns][SIZE/2+1];
        for(int col=0;col<columns;col++){int center=first+(int)((col+.5)*(last-first)/columns);double[] re=new double[SIZE],im=new double[SIZE];for(int i=0;i<SIZE;i++){int frame=center+i-SIZE/2;if(frame>=first&&frame<last){for(int ch=0;ch<input.format.getChannels();ch++)re[i]+=sample(input,frame,ch)/input.format.getChannels();re[i]*=window(i);}}fft(re,im,false);for(int i=0;i<values[col].length;i++)values[col][i]=(float)(20*Math.log10(Math.max(1e-7,Math.hypot(re[i],im[i])/(SIZE/2))));}return values;
    }
}
