package akiat.review;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.swing.*;

public final class SourceContextTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Map<String,String> snapshot(Path report)throws Exception{
        Map<String,String> hashes=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(report.resolve("desktop_edits"))){
            for(Path p:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator)
                hashes.put(p.toString(),Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }return hashes;
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get("").toAbsolutePath(),liveReport=Paths.get(args[0]);
        // Use a private metadata snapshot: the user can keep saving in their open editor.
        Path report=Files.createTempDirectory(root.resolve("outputs/desktop_test"),"context-report-");Files.createDirectories(report.resolve("suspicious"));Files.createDirectories(report.resolve("desktop_edits"));
        for(ReviewData.Item entry:ReviewData.readReport(liveReport)){
            Map<String,Object> raw=new LinkedHashMap<>(entry.raw);raw.put("audio_relative",entry.audio.toAbsolutePath().toString());
            Files.writeString(report.resolve("suspicious").resolve(entry.uid+".json"),Json.write(raw));
            Path edit=liveReport.resolve("desktop_edits").resolve(entry.uid+".json");if(Files.isRegularFile(edit))Files.copy(edit,report.resolve("desktop_edits").resolve(entry.uid+".json"));
        }
        Map<String,String> before=snapshot(report);
        ReviewData.Item item=ReviewData.readReport(report).stream().filter(i->i.uid.equals("acdd51b547eaf8f9_00014")).findFirst().get();
        Path source=SourceContext.findSource(root,item.source);ReviewData.Sound original=ReviewData.readSound(item.audio);
        check(SourceContext.windowStart(2,5)==0,"context must clamp at recording start");
        check(SourceContext.windowStart(168,5)==163,"absolute source offset");
        check(SourceContext.time(168).equals("02:48.000"),"minutes in source");
        ReviewData.Sound context=SourceContext.decode(root,source,163,22);
        check(Math.abs(context.duration-22)<.01,"requested context length");
        check(context.format.getSampleRate()==44100,"retain original MP3 sampling rate");
        check(context.peaks.length>0&&context.bytes.length>0,"decoded source audio");
        double energy=0,cross=0,reference=0;
        // Source PCM at 44.1 kHz aligns with the old 22.05 kHz fragment at +5 s.
        for(int i=1000;i<Math.min(100000,original.bytes.length/2);i++){
            int a=i*2,b=(int)((5*44100+i*2)*2);
            short x=(short)((original.bytes[a]&255)|(original.bytes[a+1]<<8));
            short y=(short)((context.bytes[b]&255)|(context.bytes[b+1]<<8));
            cross+=(double)x*y;energy+=(double)x*x;reference+=(double)y*y;
        }
        check(cross/Math.sqrt(energy*reference)>.95,"source context matches fragment timing");
        Path exported=root.resolve("outputs/desktop_test/context-export-"+UUID.randomUUID()+".mp3");
        SourceContext.exportMp3(root,source,exported,168,170.5);
        ReviewData.Sound exportedSound=SourceContext.decode(root,exported,0,10);
        check(Math.abs(exportedSound.duration-2.5)<.05,"exported MP3 selected duration");
        check(exportedSound.format.getSampleRate()==44100,"export retains original sample rate");
        byte[] exportedBefore=Files.readAllBytes(exported);boolean refused=false;
        try{SourceContext.exportMp3(root,source,exported,170,171);}catch(java.io.IOException expected){refused=true;}
        check(refused&&Arrays.equals(exportedBefore,Files.readAllBytes(exported)),"existing export is never overwritten");
        boolean invalid=false;try{SourceContext.exportMp3(root,source,exported.resolveSibling("invalid.mp3"),171,170);}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"reversed export rejected");
        List<ReviewData.Item> items=ReviewData.readReport(report);
        List<SourceContext.Span> spans=SourceContext.readSpans(report,items,item,original.duration,1,original.duration-1,"draft");
        check(spans.size()>1,"neighboring fragments included");
        SourceContext.Span current=spans.stream().filter(s->s.current).findFirst().get();
        check(current.keptStart==169&&current.keptEnd==179&&current.status.equals("draft"),"current unsaved trim and state");
        check(!current.overlaps(180,190)&&current.overlaps(179,190),"exclusive interval edges");
        ReviewData.Item duplicate=new ReviewData.Item();duplicate.uid=item.uid+"_copy_test123";duplicate.source=item.source;duplicate.sourceStart=item.sourceStart;duplicate.raw=item.raw;duplicate.original=item.original;duplicate.status="excluded";
        List<ReviewData.Item> synthetic=new ArrayList<>(items);synthetic.add(duplicate);
        List<SourceContext.Span> withCopy=SourceContext.readSpans(report,synthetic,item,original.duration,1,11,"draft");
        check(withCopy.size()==spans.size()+1&&withCopy.stream().anyMatch(s->s.uid.equals(duplicate.uid)&&s.status.equals("excluded")&&!s.current),"overlapping excluded duplicate has own row");
        SwingUtilities.invokeAndWait(()->{try{
            SourceContext dialog=new SourceContext(null,root,item,original.duration,1,original.duration-1,2);
            dialog.setSpans(withCopy);
            javax.swing.Timer closer=new javax.swing.Timer(5000,e->{try{
                java.lang.reflect.Field modeField=SourceContext.class.getDeclaredField("selectMode");modeField.setAccessible(true);((JCheckBox)modeField.get(dialog)).setSelected(true);
                java.lang.reflect.Field waveField=SourceContext.class.getDeclaredField("timeline");waveField.setAccessible(true);JComponent wave=(JComponent)waveField.get(dialog);
                long now=System.currentTimeMillis();int width=wave.getWidth();
                wave.dispatchEvent(new java.awt.event.MouseEvent(wave,java.awt.event.MouseEvent.MOUSE_PRESSED,now,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,width*3/4,60,1,false,java.awt.event.MouseEvent.BUTTON1));
                wave.dispatchEvent(new java.awt.event.MouseEvent(wave,java.awt.event.MouseEvent.MOUSE_DRAGGED,now+1,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,width/4,60,0,false,java.awt.event.MouseEvent.NOBUTTON));
                java.lang.reflect.Field startField=SourceContext.class.getDeclaredField("selectionStart"),endField=SourceContext.class.getDeclaredField("selectionEnd");startField.setAccessible(true);endField.setAccessible(true);
                check(Math.abs(startField.getDouble(dialog)-168.5)<.05&&Math.abs(endField.getDouble(dialog)-179.5)<.05,"reverse mouse drag maps to ordered absolute times");
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g=image.createGraphics();dialog.paint(g);g.dispose();
                javax.imageio.ImageIO.write(image,"png",root.resolve("outputs/desktop_test/source-context.png").toFile());
            }catch(Exception ex){throw new RuntimeException(ex);}finally{dialog.dispose();}});
            closer.setRepeats(false);closer.start();dialog.setVisible(true);
        }catch(Exception ex){throw new RuntimeException(ex);}});
        check(before.equals(snapshot(report)),"all existing editorial files remain unchanged");
        System.out.println("PASS: original MP3, offsets, waveform alignment, native rate, selection drag, MP3 export duration, overwrite protection, unchanged editorial materials");
        System.exit(0);
    }
}
