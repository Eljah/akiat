package akiat.review;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

final class ReviewData {
    static final Pattern WORD=Pattern.compile("\\p{L}+(?:[-’']\\p{L}+)*");
    static final class Word {
        String text,kind="approximate",flags=""; int from,to; double start,end;
        Word(String text,int from,int to){this.text=text;this.from=from;this.to=to;}
    }
    static final class Item {
        Map<String,Object> raw;Path json,audio;String uid,source,original;double sourceStart;int issues;
        public String toString(){return uid+"  ·  "+issues+" пометок";}
    }
    static final class Sound {
        AudioFormat format;byte[] bytes;double duration;float[] peaks;
    }
    static List<Item> readReport(Path report)throws IOException{
        List<Item> items=new ArrayList<>();
        try(java.util.stream.Stream<Path> paths=Files.list(report.resolve("suspicious"))){
            for(Path p:(Iterable<Path>)paths.filter(x->x.toString().endsWith(".json"))::iterator){
                Map<String,Object> r=Json.object(Json.parse(Files.readString(p,StandardCharsets.UTF_8)));
                Item i=new Item();i.raw=r;i.json=p;i.uid=(String)r.get("uid");i.source=(String)r.get("source");i.original=(String)r.get("text");i.sourceStart=((Number)r.get("start")).doubleValue();i.issues=((Number)r.get("issue_count")).intValue();
                if(!i.uid.matches("[A-Za-z0-9_-]+"))throw new IOException("Invalid clip ID");
                i.audio=report.resolve((String)r.get("audio_relative")).normalize();
                if(!Files.isRegularFile(i.audio))throw new IOException("WAV не найден: "+i.audio);
                items.add(i);
            }
        }
        items.sort(Comparator.comparing((Item i)->i.source).thenComparingDouble(i->i.sourceStart));return items;
    }
    static Sound readSound(Path path)throws Exception{
        Sound s=new Sound();
        try(AudioInputStream input=AudioSystem.getAudioInputStream(path.toFile())){
            AudioFormat f=input.getFormat();AudioFormat pcm=new AudioFormat(f.getSampleRate(),16,f.getChannels(),true,false);
            try(AudioInputStream decoded=AudioSystem.getAudioInputStream(pcm,input)){
                s.format=pcm;s.bytes=decoded.readAllBytes();s.duration=s.bytes.length/(pcm.getFrameSize()*pcm.getFrameRate());
                int window=Math.max(1,(int)(pcm.getSampleRate()*.025));int frames=s.bytes.length/pcm.getFrameSize();
                s.peaks=new float[(frames+window-1)/window];
                for(int frame=0;frame<frames;frame++){int offset=frame*pcm.getFrameSize();short n=(short)((s.bytes[offset]&255)|(s.bytes[offset+1]<<8));s.peaks[frame/window]=Math.max(s.peaks[frame/window],Math.abs(n/32768f));}
            }
        }return s;
    }
    static long[] trimFrames(Sound sound,double start,double end){
        if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end>sound.duration+.00001||end-start<.05)
            throw new IllegalArgumentException("Оставьте не менее 0,05 с внутри границ WAV");
        long frames=sound.bytes.length/sound.format.getFrameSize();
        long a=Math.round(start*sound.format.getFrameRate()),b=Math.min(frames,Math.round(end*sound.format.getFrameRate()));
        if(a<0||b<=a||b>frames)throw new IllegalArgumentException("Некорректные границы обрезки");
        return new long[]{a,b};
    }
    static Sound slice(Sound sound,double start,double end){
        long[] frames=trimFrames(sound,start,end);Sound cut=new Sound();cut.format=sound.format;
        cut.bytes=Arrays.copyOfRange(sound.bytes,Math.toIntExact(frames[0]*sound.format.getFrameSize()),Math.toIntExact(frames[1]*sound.format.getFrameSize()));
        cut.duration=(frames[1]-frames[0])/sound.format.getFrameRate();
        int first=(int)(start/.025),last=Math.min(sound.peaks.length,(int)Math.ceil(end/.025));
        cut.peaks=Arrays.copyOfRange(sound.peaks,Math.min(first,sound.peaks.length-1),Math.max(first+1,last));return cut;
    }
    static List<Word> estimate(String text,Sound sound,double start,double end){
        List<Word> words=estimate(text,slice(sound,start,end));
        for(Word w:words){w.start=Math.min(end,w.start+start);w.end=Math.min(end,w.end+start);}return words;
    }
    static List<Word> estimate(String text,Sound sound){
        List<Word> words=new ArrayList<>();Matcher m=WORD.matcher(text);int weight=0;
        while(m.find()){Word w=new Word(m.group(),m.start(),m.end());words.add(w);weight+=Math.max(1,w.text.codePointCount(0,w.text.length()));}
        if(words.isEmpty())return words;
        float max=0;for(float p:sound.peaks)max=Math.max(max,p);
        List<Integer> active=new ArrayList<>();for(int j=0;j<sound.peaks.length;j++)if(sound.peaks[j]>Math.max(.008,max*.08))active.add(j);
        if(active.isEmpty())for(int j=0;j<sound.peaks.length;j++)active.add(j);
        int used=0;for(Word w:words){double a=(double)used/weight;used+=Math.max(1,w.text.codePointCount(0,w.text.length()));double b=(double)used/weight;
            w.start=active.get(Math.min(active.size()-1,(int)(a*active.size())))*.025;
            w.end=Math.min(sound.duration,(active.get(Math.min(active.size()-1,Math.max(0,(int)Math.ceil(b*active.size())-1)))+1)*.025);
        }return words;
    }
    static void applyFlags(List<Word> words,Item item,String text){
        if(!text.equals(item.original))return;
        for(Object o:Json.array(item.raw.get("tokens"))){Map<String,Object> t=Json.object(o);int index=((Number)t.get("index")).intValue()-1;
            if(index>=0&&index<words.size()&&words.get(index).text.equals(t.get("word")))words.get(index).flags=String.join(", ",Json.array(t.get("flags")).stream().map(Object::toString).toArray(String[]::new));}
    }
    static void setStart(List<Word> words,int index,double value,double duration){
        if(!Double.isFinite(value)||value<0||value>=duration)throw new IllegalArgumentException("Время за границами WAV");
        if(index>0&&value<words.get(index-1).start)throw new IllegalArgumentException("Начало раньше предыдущего слова");
        if(index+1<words.size()&&value>=words.get(index+1).start)throw new IllegalArgumentException("Начало должно быть раньше следующего слова");
        Word w=words.get(index);w.start=value;w.kind="manual";w.end=Math.max(value,Math.min(w.end,index+1<words.size()?words.get(index+1).start:duration));
        if(index>0&&words.get(index-1).end>value){words.get(index-1).end=value;words.get(index-1).kind="manual";}
    }
    static List<Object> wordMaps(List<Word> words){List<Object> result=new ArrayList<>();for(Word w:words){Map<String,Object> m=new LinkedHashMap<>();m.put("word",w.text);m.put("start",w.start);m.put("end",w.end);m.put("kind",w.kind);result.add(m);}return result;}
    static void restoreTimes(List<Word> words,List<Object> saved,double duration,String forcedKind){
        if(saved.size()!=words.size())throw new IllegalArgumentException("Число слов не совпадает с текстом");
        double previous=-1;
        for(int j=0;j<words.size();j++){Map<String,Object> m=Json.object(saved.get(j));double a=((Number)m.get("start")).doubleValue(),b=((Number)m.get("end")).doubleValue();
            if(!words.get(j).text.equals(m.get("word"))||!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<a||b>duration+.001||a<previous)throw new IllegalArgumentException("Несовместимые метки слова "+(j+1));previous=a;
        }
        for(int j=0;j<words.size();j++){Map<String,Object> m=Json.object(saved.get(j));Word w=words.get(j);w.start=((Number)m.get("start")).doubleValue();w.end=((Number)m.get("end")).doubleValue();w.kind=forcedKind==null?String.valueOf(m.getOrDefault("kind","approximate")):forcedKind;}
    }
    static void save(Path report,Item item,String text,List<Word> words,String status)throws IOException{
        try{Sound sound=readSound(item.audio);save(report,item,text,words,status,sound,0,sound.duration);}catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
    }
    static void save(Path report,Item item,String text,List<Word> words,String status,Sound sound,double trimStart,double trimEnd)throws IOException{
        if(text.trim().isEmpty()&&!status.equals("excluded"))throw new IllegalArgumentException("Пустой текст можно только исключить");
        long[] frames=trimFrames(sound,trimStart,trimEnd);double rate=sound.format.getFrameRate();trimStart=frames[0]/rate;trimEnd=frames[1]/rate;
        Path dir=report.resolve("desktop_edits");Files.createDirectories(dir);
        boolean trimmed=frames[0]!=0||frames[1]!=sound.bytes.length/sound.format.getFrameSize();Path output=item.audio;
        List<Object> adjusted=new ArrayList<>();
        for(Word word:words){if(word.start<trimStart-.001||word.end>trimEnd+.001)throw new IllegalArgumentException("Метка слова за обрезкой: "+word.text+". Пересчитайте позиции или исправьте текст.");Map<String,Object> entry=new LinkedHashMap<>();entry.put("word",word.text);entry.put("start",Math.max(0,word.start-trimStart));entry.put("end",Math.min(trimEnd-trimStart,word.end-trimStart));entry.put("kind",word.kind);adjusted.add(entry);}
        if(trimmed){
            Path wavDir=dir.resolve("wav");Files.createDirectories(wavDir);output=wavDir.resolve(item.uid+"_"+frames[0]+"-"+frames[1]+".wav");
            Path tmpWav=Files.createTempFile(wavDir,item.uid,".tmp");
            int offset=Math.toIntExact(frames[0]*sound.format.getFrameSize()),length=Math.toIntExact((frames[1]-frames[0])*sound.format.getFrameSize());
            try(AudioInputStream input=new AudioInputStream(new ByteArrayInputStream(sound.bytes,offset,length),sound.format,frames[1]-frames[0])){AudioSystem.write(input,AudioFileFormat.Type.WAVE,tmpWav.toFile());}
            replace(tmpWav,output);
        }
        Map<String,Object> m=new LinkedHashMap<>();m.put("schema_version",2);m.put("uid",item.uid);m.put("original_text",item.original);m.put("corrected_text",text);m.put("status",status);m.put("audio",output.toString());m.put("original_audio",item.audio.toString());m.put("source",item.source);m.put("source_start",item.sourceStart);m.put("source_trimmed_start",item.sourceStart+trimStart);m.put("source_trimmed_end",item.sourceStart+trimEnd);m.put("trim_start",trimStart);m.put("trim_end",trimEnd);m.put("trim_start_frame",frames[0]);m.put("trim_end_frame_exclusive",frames[1]);m.put("trimmed_duration",trimEnd-trimStart);m.put("updated",Instant.now().toString());m.put("words_time_reference","original_wav");m.put("words",wordMaps(words));m.put("words_for_saved_audio",adjusted);
        Path txtDir=dir.resolve("txt");Files.createDirectories(txtDir);Path tmpText=Files.createTempFile(txtDir,item.uid,".tmp");Files.writeString(tmpText,text,StandardCharsets.UTF_8);replace(tmpText,txtDir.resolve(item.uid+".txt"));
        Path dest=dir.resolve(item.uid+".json");Path temp=Files.createTempFile(dir,item.uid,".tmp");
        Files.writeString(temp,Json.write(m),StandardCharsets.UTF_8);
        replace(temp,dest);
    }
    static void replace(Path temp,Path dest)throws IOException{
        try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);}
    }
}
