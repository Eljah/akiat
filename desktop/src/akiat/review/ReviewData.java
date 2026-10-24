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
        String text,kind="approximate",flags=""; boolean edited; int from,to; double start,end;
        Word(String text,int from,int to){this.text=text;this.from=from;this.to=to;}
    }
    static final class Item {
        Map<String,Object> raw,recognition;Path json,audio;String uid,source,original,status="new";double sourceStart;int issues;
        public String toString(){return (status.equals("excluded")?"[ИСКЛЮЧЁН] ":status.equals("reviewed")?"[ПРОВЕРЕН] ":status.equals("draft")?"[ЧЕРНОВИК] ":"")+uid+"  ·  "+issues+" пометок";}
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
                Path saved=report.resolve("desktop_edits").resolve(i.uid+".json");
                if(Files.isRegularFile(saved)){Map<String,Object> edit=Json.object(Json.parse(Files.readString(saved,StandardCharsets.UTF_8)));if(i.original.equals(edit.get("original_text")))i.status=String.valueOf(edit.getOrDefault("status","draft"));}
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
        List<Object> tokens=Json.array((item.recognition==null?item.raw:item.recognition).get("tokens"));int n=tokens.size(),m=words.size();int[][] matches=new int[n+1][m+1];
        for(Word w:words){w.flags="";w.edited=true;}
        for(int i=n-1;i>=0;i--)for(int j=m-1;j>=0;j--)matches[i][j]=words.get(j).text.equals(Json.object(tokens.get(i)).get("word"))?1+matches[i+1][j+1]:Math.max(matches[i+1][j],matches[i][j+1]);
        for(int i=0,j=0;i<n&&j<m;){Map<String,Object> t=Json.object(tokens.get(i));Word w=words.get(j);if(w.text.equals(t.get("word"))){w.edited=false;w.flags=String.join(", ",Json.array(t.get("flags")).stream().map(Object::toString).toArray(String[]::new));i++;j++;}else if(matches[i+1][j]>=matches[i][j+1])i++;else j++;}
    }
    static byte[] playbackBytes(Sound sound,double speed){
        if(speed!=.5&&speed!=.75&&speed!=1)throw new IllegalArgumentException("Unsupported playback speed");
        if(speed==1)return sound.bytes;
        int frameSize=sound.format.getFrameSize(),frames=sound.bytes.length/frameSize,count=(int)Math.ceil(frames/speed);byte[] output=new byte[Math.multiplyExact(count,frameSize)];
        for(int j=0;j<count;j++){double position=j*speed;int a=Math.min(frames-1,(int)position),b=Math.min(frames-1,a+1);double fraction=position-a;
            for(int channel=0;channel<frameSize;channel+=2){int ia=a*frameSize+channel,ib=b*frameSize+channel;short va=(short)((sound.bytes[ia]&255)|(sound.bytes[ia+1]<<8)),vb=(short)((sound.bytes[ib]&255)|(sound.bytes[ib+1]<<8));int sample=(int)Math.round(va+(vb-va)*fraction),out=j*frameSize+channel;output[out]=(byte)sample;output[out+1]=(byte)(sample>>8);}}
        return output;
    }
    static void setStart(List<Word> words,int index,double value,double duration){
        if(!Double.isFinite(value)||value<0||value>=duration)throw new IllegalArgumentException("Время за границами WAV");
        if(index>0&&value<words.get(index-1).start)throw new IllegalArgumentException("Начало раньше предыдущего слова");
        if(index+1<words.size()&&value>=words.get(index+1).start)throw new IllegalArgumentException("Начало должно быть раньше следующего слова");
        Word w=words.get(index);w.start=value;w.kind="manual";w.end=Math.max(value,Math.min(w.end,index+1<words.size()?words.get(index+1).start:duration));
        if(index>0&&words.get(index-1).end>value){words.get(index-1).end=value;words.get(index-1).kind="manual";}
    }
    static List<Object> wordMaps(List<Word> words){List<Object> result=new ArrayList<>();for(Word w:words){Map<String,Object> m=new LinkedHashMap<>();m.put("word",w.text);m.put("start",w.start);m.put("end",w.end);m.put("kind",w.kind);result.add(m);}return result;}
    static String trimState(Word word,double start,double end){return word.end<=start||word.start>=end?"outside":word.start<start||word.end>end?"partial":"inside";}
    static List<Word> reconcile(String text,Sound sound,List<Word> old){
        List<Word> fresh=estimate(text,sound);int n=old.size(),m=fresh.size();int[][] lengths=new int[n+1][m+1];boolean[] matched=new boolean[m];int[] source=new int[m];Arrays.fill(source,-1);
        for(int i=n-1;i>=0;i--)for(int j=m-1;j>=0;j--)lengths[i][j]=old.get(i).text.equals(fresh.get(j).text)?1+lengths[i+1][j+1]:Math.max(lengths[i+1][j],lengths[i][j+1]);
        for(int i=0,j=0;i<n&&j<m;){if(old.get(i).text.equals(fresh.get(j).text)){source[j]=i;Word a=old.get(i++),b=fresh.get(j);b.start=a.start;b.end=a.end;b.kind=a.kind;matched[j++]=true;}else if(lengths[i+1][j]>=lengths[i][j+1])i++;else j++;}
        for(int j=0;j<m;){
            if(matched[j]){j++;continue;}int first=j;while(j<m&&!matched[j])j++;
            int oldFirst=first==0?0:source[first-1]+1,oldEnd=j==m?n:source[j];
            double a,b;
            if(oldFirst<oldEnd){a=old.get(oldFirst).start;b=old.get(oldEnd-1).end;}
            else if(first>0&&(j==m||fresh.get(first-1).end>=fresh.get(j).start)){
                // A surviving prefix plus new words shares its original interval.
                first--;a=fresh.get(first).start;b=fresh.get(first).end;
            }else{a=first==0?0:fresh.get(first-1).end;b=j==m?sound.duration:fresh.get(j).start;}
            if(first>0)a=Math.max(a,fresh.get(first-1).start);
            if(j<m)b=Math.min(b,fresh.get(j).start);b=Math.max(a,b);
            for(int k=first;k<j;k++){fresh.get(k).start=a+(b-a)*(k-first)/(j-first);fresh.get(k).end=a+(b-a)*(k-first+1)/(j-first);fresh.get(k).kind="approximate";}
        }
        return fresh;
    }
    static boolean restoreEditableTimes(List<Word> words,List<Object> saved,double duration){
        List<Object> repaired=new ArrayList<>();double previous=0;boolean changed=false;
        for(Object value:saved){Map<String,Object> entry=new LinkedHashMap<>(Json.object(value));
            double a=((Number)entry.get("start")).doubleValue(),b=((Number)entry.get("end")).doubleValue();
            if(!Double.isFinite(a)||!Double.isFinite(b))throw new IllegalArgumentException("Non-finite saved timing");
            double start=Math.max(previous,Math.min(duration,Math.max(0,a))),end=Math.max(start,Math.min(duration,b));
            if(start!=a||end!=b){changed=true;entry.put("start",start);entry.put("end",end);entry.put("kind","approximate");}
            previous=start;repaired.add(entry);
        }
        restoreTimes(words,repaired,duration,null);return changed;
    }
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
    static String effectiveText(String text,List<Word> words,double start,double end){
        StringBuilder result=new StringBuilder();
        for(int i=0;i<words.size();i++){
            Word w=words.get(i);if(!trimState(w,start,end).equals("inside"))continue;
            int to=i+1<words.size()?words.get(i+1).from:text.length();
            if(result.length()==0&&i==0)result.append(text,0,w.from);
            result.append(text,w.from,to);
        }
        return result.toString().trim();
    }
    static void save(Path report,Item item,String text,List<Word> words,String status,Sound sound,double trimStart,double trimEnd)throws IOException{
        save(report,item,text,words,status,sound,trimStart,trimEnd,effectiveText(text,words,trimStart,trimEnd),false,false);
    }
    static void save(Path report,Item item,String text,List<Word> words,String status,Sound sound,double trimStart,double trimEnd,String effective,boolean manual,boolean stale)throws IOException{
        if(effective.trim().isEmpty()&&!status.equals("excluded"))throw new IllegalArgumentException("Эффективный текст пуст. Заполните его или исключите фрагмент.");
        if(text.trim().isEmpty()&&!status.equals("excluded"))throw new IllegalArgumentException("Пустой текст можно только исключить");
        long[] frames=trimFrames(sound,trimStart,trimEnd);double rate=sound.format.getFrameRate();trimStart=frames[0]/rate;trimEnd=frames[1]/rate;
        Path dir=report.resolve("desktop_edits");Files.createDirectories(dir);
        boolean trimmed=frames[0]!=0||frames[1]!=sound.bytes.length/sound.format.getFrameSize();Path output=item.audio;
        List<Object> adjusted=new ArrayList<>();
        List<Object> trimStates=new ArrayList<>();boolean needsTextReview=false;
        for(Word word:words){String state=trimState(word,trimStart,trimEnd);trimStates.add(state);needsTextReview|=!state.equals("inside");if(state.equals("outside"))continue;Map<String,Object> entry=new LinkedHashMap<>();entry.put("word",word.text);entry.put("start",Math.max(0,word.start-trimStart));entry.put("end",Math.min(trimEnd-trimStart,word.end-trimStart));entry.put("kind",word.kind);entry.put("clipped",state.equals("partial"));adjusted.add(entry);}
        if(trimmed){
            Path wavDir=dir.resolve("wav");Files.createDirectories(wavDir);output=wavDir.resolve(item.uid+"_"+frames[0]+"-"+frames[1]+".wav");
            Path tmpWav=Files.createTempFile(wavDir,item.uid,".tmp");
            int offset=Math.toIntExact(frames[0]*sound.format.getFrameSize()),length=Math.toIntExact((frames[1]-frames[0])*sound.format.getFrameSize());
            try(AudioInputStream input=new AudioInputStream(new ByteArrayInputStream(sound.bytes,offset,length),sound.format,frames[1]-frames[0])){AudioSystem.write(input,AudioFileFormat.Type.WAVE,tmpWav.toFile());}
            replace(tmpWav,output);
        }
        Map<String,Object> m=new LinkedHashMap<>();m.put("schema_version",2);m.put("uid",item.uid);m.put("original_text",item.original);m.put("corrected_text",text);m.put("status",status);m.put("audio",output.toString());m.put("original_audio",item.audio.toString());m.put("source",item.source);m.put("source_start",item.sourceStart);m.put("source_trimmed_start",item.sourceStart+trimStart);m.put("source_trimmed_end",item.sourceStart+trimEnd);m.put("trim_start",trimStart);m.put("trim_end",trimEnd);m.put("trim_start_frame",frames[0]);m.put("trim_end_frame_exclusive",frames[1]);m.put("trimmed_duration",trimEnd-trimStart);m.put("updated",Instant.now().toString());m.put("words_time_reference","original_wav");m.put("words",wordMaps(words));m.put("words_for_saved_audio",adjusted);
        Path txtDir=dir.resolve("txt");Files.createDirectories(txtDir);Path tmpText=Files.createTempFile(txtDir,item.uid,".tmp");Files.writeString(tmpText,effective,StandardCharsets.UTF_8);replace(tmpText,txtDir.resolve(item.uid+".txt"));
        m.put("schema_version",3);m.put("word_trim_states",trimStates);m.put("text_requires_review",needsTextReview);
        m.put("schema_version",5);m.put("recognition",item.recognition);m.put("effective_text",effective);m.put("training_text",effective);m.put("effective_text_manual",manual);m.put("effective_text_needs_review",stale);m.put("training_eligible",status.equals("reviewed")&&!stale&&!effective.trim().isEmpty());
        Path dest=dir.resolve(item.uid+".json");Path temp=Files.createTempFile(dir,item.uid,".tmp");
        Files.writeString(temp,Json.write(m),StandardCharsets.UTF_8);
        replace(temp,dest);
    }
    static void replace(Path temp,Path dest)throws IOException{
        try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);}
    }
}
