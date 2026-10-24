package akiat.review;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ReviewTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Map<String,Object> j=new LinkedHashMap<>();j.put("text","Әни әйтте: \"Исәнме!\"\nҖир");j.put("items",Arrays.asList(1,true,null));check(Json.write(Json.parse(Json.write(j))).contains("Әни"),"JSON UTF-8");
        ReviewData.Sound sound=new ReviewData.Sound();sound.duration=2;sound.peaks=new float[80];
        List<ReviewData.Word> w=ReviewData.estimate("Әни килә.",sound);check(w.size()==2,"tokenize Tatar");check(w.get(0).start>=0&&w.get(1).end<=2,"bounded estimates");
        ReviewData.setStart(w,1,1.2,2);check(w.get(1).kind.equals("manual"),"manual provenance");
        try{ReviewData.setStart(w,1,-1,2);throw new AssertionError("negative accepted");}catch(IllegalArgumentException expected){}
        try{ReviewData.setStart(w,0,1.5,2);throw new AssertionError("crossing accepted");}catch(IllegalArgumentException expected){}
        List<Object> times=ReviewData.wordMaps(w);List<ReviewData.Word> restored=ReviewData.estimate("Әни килә.",sound);ReviewData.restoreTimes(restored,times,2,null);check(restored.get(1).start==1.2,"restore time");
        List<ReviewData.Word> different=ReviewData.estimate("Бала килә.",sound);try{ReviewData.restoreTimes(different,times,2,"imported");throw new AssertionError("mismatch accepted");}catch(IllegalArgumentException expected){}
        ReviewData.Item flagsItem=new ReviewData.Item();flagsItem.original="A Wrong B";flagsItem.raw=Json.object(Json.parse("{\"tokens\":[{\"word\":\"A\",\"flags\":[\"CHECK\"]},{\"word\":\"Wrong\",\"flags\":[\"UNKNOWN\"]},{\"word\":\"B\",\"flags\":[\"CHECK\"]}]}"));
        List<ReviewData.Word> changed=ReviewData.estimate("A Fixed B",sound);ReviewData.applyFlags(changed,flagsItem,"A Fixed B");
        check(changed.get(0).flags.equals("CHECK")&&changed.get(2).flags.equals("CHECK"),"unmodified flagged words retain marks after correction elsewhere");
        check(changed.get(1).edited&&changed.get(1).flags.isEmpty()&&!changed.get(2).edited,"changed word distinguished without stale flags");
        changed=ReviewData.estimate("Wrong B",sound);ReviewData.applyFlags(changed,flagsItem,"Wrong B");check(changed.get(0).flags.equals("UNKNOWN"),"flags survive deletion before word");
        Path report=Paths.get(args[0]);List<ReviewData.Item> items=ReviewData.readReport(report);check(items.size()==609,"real report count");ReviewData.Item item=items.get(0);String original=Files.readString(item.json);
        ReviewData.Sound wav=ReviewData.readSound(item.audio);check(wav.duration>0&&wav.bytes.length>0,"WAV read");
        byte[] slow=ReviewData.playbackBytes(wav,.5);check(slow.length==wav.bytes.length*2,"half speed doubles duration");
        int frameSize=wav.format.getFrameSize();for(int i=0;i<wav.bytes.length/frameSize;i+=101)for(int b=0;b<frameSize;b++)check(slow[i*2*frameSize+b]==wav.bytes[i*frameSize+b],"half speed retains original samples at exact positions");
        check(ReviewData.playbackBytes(wav,1)==wav.bytes,"normal speed uses unchanged original");
        List<ReviewData.Word> words=ReviewData.estimate(item.original,wav);ReviewData.applyFlags(words,item,item.original);check(!words.isEmpty(),"words from real clip");
        Path test=Paths.get(args[1]);Files.createDirectories(test);ReviewData.save(test,item,item.original,words,"reviewed");
        Map<String,Object> saved=Json.object(Json.parse(Files.readString(test.resolve("desktop_edits").resolve(item.uid+".json"),StandardCharsets.UTF_8)));
        check(saved.get("status").equals("reviewed")&&saved.get("corrected_text").equals(item.original),"save correction");
        ReviewData.restoreTimes(words,Json.array(saved.get("words")),wav.duration,null);
        double begin=1.125,end=wav.duration-.725;
        long[] frames=ReviewData.trimFrames(wav,begin,end);
        List<ReviewData.Word> cutWords=ReviewData.estimate(item.original,wav,begin,end);
        ReviewData.save(test,item,item.original,cutWords,"draft",wav,begin,end);
        Map<String,Object> cutEdit=Json.object(Json.parse(Files.readString(test.resolve("desktop_edits").resolve(item.uid+".json"),StandardCharsets.UTF_8)));
        ReviewData.Sound cut=ReviewData.readSound(Paths.get((String)cutEdit.get("audio")));
        byte[] expected=Arrays.copyOfRange(wav.bytes,(int)frames[0]*wav.format.getFrameSize(),(int)frames[1]*wav.format.getFrameSize());
        check(Arrays.equals(cut.bytes,expected),"trim exact PCM frames without re-encoding");
        check(Math.abs(cut.duration-((Number)cutEdit.get("trimmed_duration")).doubleValue())<.001,"trim duration");
        check(Math.abs(((Number)cutEdit.get("source_trimmed_start")).doubleValue()-item.sourceStart-frames[0]/wav.format.getFrameRate())<.001,"source offset");
        ReviewData.restoreTimes(cutWords,Json.array(cutEdit.get("words")),wav.duration,null);
        List<ReviewData.Word> cutRelative=ReviewData.estimate(item.original,cut);ReviewData.restoreTimes(cutRelative,Json.array(cutEdit.get("words_for_saved_audio")),cut.duration,null);
        for(double[] invalid:new double[][]{{2,1},{-1,2},{0,wav.duration+1},{1,1.001},{Double.NaN,2}}){try{ReviewData.trimFrames(wav,invalid[0],invalid[1]);throw new AssertionError("Invalid trim accepted");}catch(IllegalArgumentException ok){}}
        ReviewData.save(test,item,item.original,words,"draft",wav,0,wav.duration);
        Map<String,Object> reset=Json.object(Json.parse(Files.readString(test.resolve("desktop_edits").resolve(item.uid+".json"),StandardCharsets.UTF_8)));
        check(reset.get("audio").equals(item.audio.toString()),"reset trim references original");
        List<ReviewData.Word> anchored=ReviewData.estimate("A B C",wav);
        for(int i=0;i<3;i++){anchored.get(i).start=i*2;anchored.get(i).end=i*2+1;anchored.get(i).kind="manual";}
        String anchoredBefore=Json.write(ReviewData.wordMaps(anchored));
        ReviewData.save(test,item,"A B C",anchored,"draft",wav,1.5,3.5);
        Map<String,Object> fixed=Json.object(Json.parse(Files.readString(test.resolve("desktop_edits").resolve(item.uid+".json"))));
        check(anchoredBefore.equals(Json.write(fixed.get("words"))),"trim save preserves original word times");
        check(Json.array(fixed.get("words_for_saved_audio")).size()==1,"outside words omitted from cropped timing list");
        check(Boolean.TRUE.equals(fixed.get("text_requires_review")),"cropped speech requires text review");
        List<ReviewData.Word> remaining=ReviewData.reconcile("B",wav,anchored);
        check(remaining.size()==1&&remaining.get(0).start==2&&remaining.get(0).end==3&&remaining.get(0).kind.equals("manual"),"deleting edge words preserves remaining timestamps");
        check(Files.readString(item.json).equals(original),"original unchanged");
        System.out.println("PASS: JSON, word timings, 609 pairs, save/reload, exact PCM trimming, source offsets, adjusted timestamps, invalid cuts and reset");
    }
}
