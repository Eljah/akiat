package akiat.review;

import javax.swing.*;
import javax.sound.sampled.*;
import java.nio.file.*;
import java.util.*;

public final class EqualizerTests {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static double amplitude(ReviewData.Sound s,double hz){double re=0,im=0;int n=s.bytes.length/2;for(int i=2048;i<n-2048;i++){double v=EqualizerData.sample(s,i,0),angle=2*Math.PI*hz*i/s.format.getSampleRate();re+=v*Math.cos(angle);im+=v*Math.sin(angle);}return 2*Math.hypot(re,im)/(n-4096);}
    static void save(ReviewData.Sound s,Path path)throws Exception{try(AudioInputStream stream=new AudioInputStream(new java.io.ByteArrayInputStream(s.bytes),s.format,s.bytes.length/s.format.getFrameSize())){AudioSystem.write(stream,AudioFileFormat.Type.WAVE,path.toFile());}}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get("").toAbsolutePath(),test=root.resolve("outputs/desktop_test/equalizer");Files.createDirectories(test);
        ReviewData.Sound s=new ReviewData.Sound();s.format=new AudioFormat(22050,16,1,true,false);s.duration=2;s.bytes=new byte[44100*2];s.peaks=new float[80];
        for(int i=0;i<44100;i++){int value=(int)Math.round(6000*(Math.sin(2*Math.PI*200*i/22050)+Math.sin(2*Math.PI*2000*i/22050)));s.bytes[2*i]=(byte)value;s.bytes[2*i+1]=(byte)(value>>8);}
        EqualizerData.Settings setting=new EqualizerData.Settings(150,300,-24);ReviewData.Sound filtered=EqualizerData.filter(s,setting);check(filtered.bytes.length==s.bytes.length,"unchanged frames");
        double inside=20*Math.log10(amplitude(filtered,200)/amplitude(s,200)),outside=20*Math.log10(amplitude(filtered,2000)/amplitude(s,2000));check(Math.abs(inside+24)<.5,"specified band attenuation: "+inside);check(Math.abs(outside)<.05,"outside band preserved: "+outside);
        check(EqualizerData.filter(s,new EqualizerData.Settings(150,300,0))==s,"0 dB bit-exact bypass");
        save(s,test.resolve("before.wav"));save(filtered,test.resolve("java-after.wav"));
        ReviewData.Sound noise=new ReviewData.Sound();noise.format=s.format;noise.duration=s.duration;noise.bytes=new byte[s.bytes.length];noise.peaks=s.peaks;
        for(int i=0;i<44100;i++){int value=(int)Math.round(6000*Math.sin(2*Math.PI*200*i/22050));noise.bytes[2*i]=(byte)value;noise.bytes[2*i+1]=(byte)(value>>8);}save(noise,test.resolve("noise.wav"));
        ReviewData.Item reference=new ReviewData.Item();reference.uid="noise";reference.audio=test.resolve("noise.wav");Map<String,Object> profile=EqualizerData.profile(reference,0,2);
        EqualizerData.Settings profiled=new EqualizerData.Settings(0,11025,-12,profile,0,0);ReviewData.Sound suppressed=EqualizerData.filter(s,profiled);
        double noiseReduction=20*Math.log10(amplitude(suppressed,200)/amplitude(s,200)),voiceChange=20*Math.log10(amplitude(suppressed,2000)/amplitude(s,2000));check(noiseReduction< -10&&noiseReduction> -12.5,"profile attenuates matching background: "+noiseReduction);check(Math.abs(voiceChange)<.1,"strong out-of-profile speech preserved: "+voiceChange);
        save(suppressed,test.resolve("java-profile.wav"));EqualizerData.write(test.resolve("profile.json"),profiled.json());
        EqualizerData.Settings smooth=new EqualizerData.Settings(0,11025,-12,profile,3,30);save(EqualizerData.filter(s,smooth),test.resolve("java-profile-smooth.wav"));EqualizerData.write(test.resolve("profile-smooth.json"),smooth.json());
        check(EqualizerData.filter(s,new EqualizerData.Settings(0,11025,0,profile,0,30))==s,"profile 0 dB is bit exact");boolean mismatch=false;try{profiled.validate(44100);}catch(IllegalArgumentException expected){mismatch=true;}check(mismatch,"profile rate mismatch rejected");check(EqualizerData.settings(profiled.json()).profile!=null,"profile settings survive serialization");
        ReviewData.Item item=new ReviewData.Item();item.uid="test";item.audio=test.resolve("before.wav");String hash=EqualizerData.hash(item.audio);Path isolated=Files.createTempDirectory(test,"recipe-");
        EqualizerData.apply(isolated,item,setting);check(EqualizerData.applied(isolated,item).db==-24,"saved manual settings restored");EqualizerData.disable(isolated,item);check(EqualizerData.applied(isolated,item)==null,"disabled recipe");check(hash.equals(EqualizerData.hash(item.audio)),"source unchanged");
        Map<String,Object> stale=EqualizerData.read(EqualizerData.path(isolated,"test"));stale.put("enabled",true);stale.put("source_sha256","bad");EqualizerData.write(EqualizerData.path(isolated,"test"),stale);boolean rejected=false;try{EqualizerData.applied(isolated,item);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"stale source rejected");
        Path report=Paths.get(args[0]);List<ReviewData.Item> items=ReviewData.readReport(report);ReviewData.Item real=items.stream().filter(i->i.uid.equals("acdd51b547eaf8f9_00014")).findFirst().get();ReviewData.Sound realSound=ReviewData.readSound(real.audio);
        SwingUtilities.invokeLater(()->{try{
            EqualizerDialog dialog=new EqualizerDialog(null,report,items,real,realSound,1,9.294);if(args.length>1){java.lang.reflect.Field mode=EqualizerDialog.class.getDeclaredField("mode");mode.setAccessible(true);((JComboBox<?>)mode.get(dialog)).setSelectedIndex(1);}long deadline=System.currentTimeMillis()+30000;boolean[] dragged={args.length>1};int[] scopeStep={0};byte[][] initialFiltered={null};
            javax.swing.Timer timer=new javax.swing.Timer(200,null);timer.addActionListener(e->{try{
                if(System.currentTimeMillis()>deadline)throw new AssertionError("Preview timed out");java.lang.reflect.Field busy=EqualizerDialog.class.getDeclaredField("busy");busy.setAccessible(true);if(busy.getBoolean(dialog))return;
                if(!dragged[0]){java.lang.reflect.Field plot=EqualizerDialog.class.getDeclaredField("spectrum");plot.setAccessible(true);JComponent component=(JComponent)plot.get(dialog);long now=System.currentTimeMillis();component.dispatchEvent(new java.awt.event.MouseEvent(component,java.awt.event.MouseEvent.MOUSE_PRESSED,now,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,200,50,1,false,java.awt.event.MouseEvent.BUTTON1));component.dispatchEvent(new java.awt.event.MouseEvent(component,java.awt.event.MouseEvent.MOUSE_DRAGGED,now+1,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,350,50,0,false,java.awt.event.MouseEvent.NOBUTTON));dragged[0]=true;return;}
                java.lang.reflect.Field scopeField=EqualizerDialog.class.getDeclaredField("scope"),fromField=EqualizerDialog.class.getDeclaredField("from"),toField=EqualizerDialog.class.getDeclaredField("to"),filteredField=EqualizerDialog.class.getDeclaredField("filtered");for(java.lang.reflect.Field f:new java.lang.reflect.Field[]{scopeField,fromField,toField,filteredField})f.setAccessible(true);JComboBox<?> scope=(JComboBox<?>)scopeField.get(dialog);
                if(scopeStep[0]==0){check(fromField.getDouble(dialog)==1&&toField.getDouble(dialog)==9.294,"trimmed view bounds");initialFiltered[0]=((ReviewData.Sound)filteredField.get(dialog)).bytes;scope.setSelectedIndex(1);scopeStep[0]++;return;}
                if(scopeStep[0]==1){check(fromField.getDouble(dialog)==0&&toField.getDouble(dialog)==realSound.duration,"full view bounds");check(Arrays.equals(initialFiltered[0],((ReviewData.Sound)filteredField.get(dialog)).bytes),"view scope does not alter filter recipe/output");java.lang.reflect.Field slider=EqualizerDialog.class.getDeclaredField("position"),cursor=EqualizerDialog.class.getDeclaredField("cursor");slider.setAccessible(true);cursor.setAccessible(true);((JSlider)slider.get(dialog)).setValue(10000);check(Math.abs(cursor.getDouble(dialog)-realSound.duration)<.001,"full-view seek uses full duration");scope.setSelectedIndex(0);scopeStep[0]++;return;}
                if(scopeStep[0]==2){check(fromField.getDouble(dialog)==1&&toField.getDouble(dialog)==9.294,"trim restored without modification");scope.setSelectedIndex(1);scopeStep[0]++;return;}
                java.lang.reflect.Field low=EqualizerDialog.class.getDeclaredField("low"),high=EqualizerDialog.class.getDeclaredField("high");low.setAccessible(true);high.setAccessible(true);check(((Number)((JSpinner)low.get(dialog)).getValue()).doubleValue()<((Number)((JSpinner)high.get(dialog)).getValue()).doubleValue(),"drag sets frequency range");
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=image.createGraphics();dialog.paint(g);g.dispose();javax.imageio.ImageIO.write(image,"png",test.resolve(args.length>1?"profile.png":"equalizer.png").toFile());timer.stop();dialog.dispose();System.out.println("PASS: manual and profile suppression, preserved speech tone, bypass, timing, recipe undo, unchanged source, spectrum UI");System.exit(0);
            }catch(Throwable failure){failure.printStackTrace();dialog.dispose();System.exit(1);}});timer.start();dialog.setVisible(true);
        }catch(Exception ex){ex.printStackTrace();System.exit(1);}});
    }
}
