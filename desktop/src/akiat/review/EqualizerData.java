package akiat.review;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Manual, time-preserving spectral band attenuation. No learned separation. */
final class EqualizerData {
    static final int SIZE=2048,HOP=512;
    static final class Settings {
        final double low,high,db;
        final Map<String,Object> profile;
        final double level,smoothing;
        Settings(double low,double high,double db){this(low,high,db,null,0,30);}
        Settings(double low,double high,double db,Map<String,Object> profile,double level,double smoothing){this.low=low;this.high=high;this.db=db;this.profile=profile;this.level=level;this.smoothing=smoothing;}
        void validate(double rate){if(!Double.isFinite(low)||!Double.isFinite(high)||!Double.isFinite(db)||low<0||high<=low||high>rate/2||db>0||db< -60)throw new IllegalArgumentException("Проверьте диапазон частот и подавление от −60 до 0 дБ");if(profile!=null){if(((Number)profile.get("sample_rate")).doubleValue()!=rate)throw new IllegalArgumentException("Частота профиля не совпадает с WAV");if(!Double.isFinite(level)||level< -12||level>18||!Double.isFinite(smoothing)||smoothing<0||smoothing>200)throw new IllegalArgumentException("Некорректные параметры профиля");List<Object> power=Json.array(profile.get("power"));if(power.size()!=SIZE/2+1)throw new IllegalArgumentException("Некорректный профиль");for(Object p:power){double v=((Number)p).doubleValue();if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Некорректная мощность профиля");}}}
        Map<String,Object> json(){Map<String,Object> m=new LinkedHashMap<>();m.put("method",profile==null?"manual-stft-band-v1":"profile-suppression-v1");m.put("low_hz",low);m.put("high_hz",high);m.put("gain_db",db);if(profile!=null){m.put("profile",profile);m.put("profile_level_db",level);m.put("smoothing_hz",smoothing);}return m;}
        double gain(double hz){double fade=Math.max(20,(high-low)*.1),weight=hz>=low&&hz<=high?1:hz<low?Math.max(0,1-(low-hz)/fade):Math.max(0,1-(hz-high)/fade);weight=.5-.5*Math.cos(Math.PI*weight);return Math.pow(10,db*weight/20);}
    }
    static String hash(Path path)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    static Map<String,Object> read(Path path)throws Exception{return Json.object(Json.parse(Files.readString(path)));}
    static Path path(Path report,String uid){if(!uid.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid ID");return report.resolve("equalizer").resolve(uid+".json");}
    static Settings settings(Map<String,Object> m){String method=String.valueOf(m.get("method"));if(!"manual-stft-band-v1".equals(method)&&!"profile-suppression-v1".equals(method))throw new IllegalArgumentException("Неизвестный эквалайзер");return new Settings(((Number)m.get("low_hz")).doubleValue(),((Number)m.get("high_hz")).doubleValue(),((Number)m.get("gain_db")).doubleValue(),method.equals("profile-suppression-v1")?Json.object(m.get("profile")):null,((Number)m.getOrDefault("profile_level_db",0)).doubleValue(),((Number)m.getOrDefault("smoothing_hz",30)).doubleValue());}
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
    static Map<String,Object> profile(ReviewData.Item item,double from,double to)throws Exception{
        ReviewData.Sound s=ReviewData.readSound(item.audio);long[] cut=ReviewData.trimFrames(s,from,to);if(cut[1]-cut[0]<SIZE)throw new IllegalArgumentException("Образец фона слишком короткий: нужно не менее "+String.format(Locale.ROOT,"%.2f",SIZE/s.format.getSampleRate())+" с");double[] power=new double[SIZE/2+1];int count=0;
        for(int start=(int)cut[0];start+SIZE<=cut[1];start+=HOP){double[] re=new double[SIZE],im=new double[SIZE];for(int i=0;i<SIZE;i++){for(int ch=0;ch<s.format.getChannels();ch++)re[i]+=sample(s,start+i,ch)/s.format.getChannels();re[i]*=window(i);}fft(re,im,false);for(int b=0;b<power.length;b++)power[b]+=re[b]*re[b]+im[b]*im[b];count++;}
        List<Object> bins=new ArrayList<>();double total=0;for(double p:power){bins.add(p/count);total+=p/count;}if(total<1e-12)throw new IllegalArgumentException("Образец фона содержит только тишину");
        Map<String,Object> m=new LinkedHashMap<>();m.put("uid",item.uid);m.put("source_sha256",hash(item.audio));m.put("from",from);m.put("to",to);m.put("sample_rate",s.format.getSampleRate());m.put("power",bins);return m;
    }
    static double[] profileGains(double[] re,double[] im,Settings s,double rate,double[] previous){int n=SIZE/2+1;double[] raw=new double[n],smoothed=new double[n];List<Object> power=Json.array(s.profile.get("power"));double floor=Math.pow(10,s.db/20),scale=Math.pow(10,s.level/10),decay=Math.exp(-HOP/rate/.08);int radius=(int)Math.floor(s.smoothing*SIZE/rate+.5);
        for(int b=0;b<n;b++)raw[b]=Math.sqrt(Math.max(floor*floor,1-scale*((Number)power.get(b)).doubleValue()/Math.max(1e-20,re[b]*re[b]+im[b]*im[b])));
        for(int b=0;b<n;b++){int a=Math.max(0,b-radius),z=Math.min(n-1,b+radius);double sum=0;for(int j=a;j<=z;j++)sum+=raw[j];smoothed[b]=sum/(z-a+1);if(previous!=null)smoothed[b]=decay*previous[b]+(1-decay)*smoothed[b];}return smoothed;
    }
    static ReviewData.Sound residual(ReviewData.Sound before,ReviewData.Sound after){ReviewData.Sound s=new ReviewData.Sound();s.format=before.format;s.duration=before.duration;s.peaks=before.peaks;s.bytes=new byte[before.bytes.length];for(int p=0;p<s.bytes.length;p+=2){int a=(short)((before.bytes[p]&255)|(before.bytes[p+1]<<8)),b=(short)((after.bytes[p]&255)|(after.bytes[p+1]<<8)),v=Math.max(-32768,Math.min(32767,a-b));s.bytes[p]=(byte)v;s.bytes[p+1]=(byte)(v>>8);}return s;}
    static ReviewData.Sound filter(ReviewData.Sound input,Settings settings){
        settings.validate(input.format.getSampleRate());if(settings.db==0)return input;
        int frames=input.bytes.length/input.format.getFrameSize(),channels=input.format.getChannels();ReviewData.Sound output=new ReviewData.Sound();output.format=input.format;output.duration=input.duration;output.bytes=new byte[input.bytes.length];
        double[] gains=new double[SIZE];for(int i=0;i<SIZE;i++)gains[i]=settings.gain(Math.min(i,SIZE-i)*input.format.getSampleRate()/SIZE);
        for(int channel=0;channel<channels;channel++){
            double[] sum=new double[frames],weight=new double[frames],previous=null;
            for(int start=-SIZE+HOP;start<frames;start+=HOP){double[] re=new double[SIZE],im=new double[SIZE];for(int i=0;i<SIZE;i++){int frame=start+i;if(frame>=0&&frame<frames)re[i]=sample(input,frame,channel)*window(i);}fft(re,im,false);if(settings.profile!=null)previous=profileGains(re,im,settings,input.format.getSampleRate(),previous);for(int i=0;i<SIZE;i++){double gain=settings.profile==null?gains[i]:previous[Math.min(i,SIZE-i)];re[i]*=gain;im[i]*=gain;}fft(re,im,true);
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
