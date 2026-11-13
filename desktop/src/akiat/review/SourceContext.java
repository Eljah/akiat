package akiat.review;

import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only playback of a bounded window of the original recording. */
final class SourceContext extends JDialog {
    private final Path source, root;
    private final double sourceStart, duration, trimStart, trimEnd, initialCursor;
    private final JLabel info=new JLabel(" "), clock=new JLabel(" ");
    private final JSlider slider=new JSlider(0,10000);
    private final JComboBox<Integer> margin=new JComboBox<>(new Integer[]{5,15,30});
    private final JComboBox<String> speeds=new JComboBox<>(new String[]{"0,5×","0,75×","1×"});
    private final Timeline timeline=new Timeline();
    private final Lanes lanes=new Lanes();
    private List<Span> spans=new ArrayList<>(),visibleSpans=new ArrayList<>();
    private final Timer timer=new Timer(40,e->refresh());
    private ReviewData.Sound sound;
    private Clip clip;
    private double offset,speed=1;
    private boolean updating,closed;
    private int generation;
    private final JCheckBox selectMode=new JCheckBox("Выделять мышью");
    private final JSpinner selectionFrom=new JSpinner(new SpinnerNumberModel(0.0,0.0,10000000.0,.05));
    private final JSpinner selectionTo=new JSpinner(new SpinnerNumberModel(0.0,0.0,10000000.0,.05));
    private final JLabel selectionInfo=new JLabel("Выделите участок на волне или строках фрагментов");
    private double selectionStart=Double.NaN,selectionEnd=Double.NaN,previewEnd=Double.POSITIVE_INFINITY;
    private boolean selectingRange,exporting;

    private static Path ffmpeg(Path root)throws Exception {
        try(java.util.stream.Stream<Path> files=Files.list(root.resolve(".venv/Lib/site-packages/imageio_ffmpeg/binaries"))){
            return files.filter(p->p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow(()->new java.io.IOException("Не найден FFmpeg"));
        }
    }
    static void exportMp3(Path root,Path source,Path destination,double start,double end)throws Exception {
        if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end-start<.05)throw new IllegalArgumentException("Выделите не менее 0,05 с");
        Path target=destination.toAbsolutePath();
        if(Files.exists(target))throw new java.io.IOException("Файл уже существует. Выберите другое имя: "+target);
        Path temporary=Files.createTempFile(target.getParent(),"akiat-export-",".mp3"),log=Files.createTempFile("akiat-export-",".log");Process process=null;
        try {
            process=new ProcessBuilder(ffmpeg(root).toString(),"-nostdin","-v","error","-y","-threads","1","-ss",Double.toString(start),"-i",source.toString(),"-t",Double.toString(end-start),"-map","0:a:0","-vn","-map_metadata","-1","-c:a","libmp3lame","-q:a","2",temporary.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(60,TimeUnit.SECONDS))throw new java.io.IOException("Истекло время экспорта MP3");
            if(process.exitValue()!=0||Files.size(temporary)==0)throw new java.io.IOException("Ошибка экспорта: "+Files.readString(log));
            Files.move(temporary,target); // Never replace an existing recording, including the source.
        } finally {
            if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor();}
            Files.deleteIfExists(temporary);Files.deleteIfExists(log);
        }
    }
    private void select(double a,double b){selectionStart=Math.min(a,b);selectionEnd=Math.max(a,b);selectingRange=true;selectionFrom.setValue(selectionStart);selectionTo.setValue(selectionEnd);selectingRange=false;selectionInfo.setText(time(selectionStart)+" — "+time(selectionEnd)+String.format(Locale.ROOT," · %.3f с",selectionEnd-selectionStart));timeline.repaint();lanes.repaint();}
    private void installMouse(JComponent component){MouseAdapter mouse=new MouseAdapter(){double anchor;
        private double absolute(MouseEvent e){return offset+Math.max(0,Math.min(1,e.getX()/(double)component.getWidth()))*sound.duration;}
        public void mousePressed(MouseEvent e){if(sound==null||!SwingUtilities.isLeftMouseButton(e))return;anchor=absolute(e);if(selectMode.isSelected()){if(clip!=null)clip.stop();select(anchor,anchor);}else{previewEnd=Double.POSITIVE_INFINITY;seek(anchor-offset);}}
        public void mouseDragged(MouseEvent e){if(sound!=null&&selectMode.isSelected()&&(e.getModifiersEx()&InputEvent.BUTTON1_DOWN_MASK)!=0)select(anchor,absolute(e));}
    };component.addMouseListener(mouse);component.addMouseMotionListener(mouse);}
    private void validateSelection()throws Exception {
        selectionFrom.commitEdit();selectionTo.commitEdit();double a=((Number)selectionFrom.getValue()).doubleValue(),b=((Number)selectionTo.getValue()).doubleValue();
        if(sound==null||!Double.isFinite(a)||!Double.isFinite(b)||a<offset||b>offset+sound.duration+.00001||b-a<.05)throw new IllegalArgumentException("Выберите не менее 0,05 с внутри загруженного контекста");select(a,b);
    }
    private void exportSelection(){if(exporting)return;try{
        validateSelection();double a=selectionStart,b=selectionEnd;
        JFileChooser chooser=new JFileChooser(root.resolve("outputs").toFile());chooser.setDialogTitle("Экспорт выделения в отдельный MP3");chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MP3","mp3"));
        String base=source.getFileName().toString().replaceFirst("(?i)\\.mp3$","");chooser.setSelectedFile(new java.io.File(base+String.format(Locale.ROOT,"_%.3f-%.3f.mp3",a,b)));
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path output=chooser.getSelectedFile().toPath();if(!output.toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))output=output.resolveSibling(output.getFileName()+".mp3");
        if(Files.exists(output))throw new java.io.IOException("Файл уже существует. Выберите другое имя.");
        final Path target=output;exporting=true;selectionInfo.setText("Экспорт MP3…");
        new SwingWorker<Void,Void>(){
            protected Void doInBackground()throws Exception{exportMp3(root,source,target,a,b);return null;}
            protected void done(){
                exporting=false;
                try{get();}catch(Exception ex){if(!closed)JOptionPane.showMessageDialog(SourceContext.this,ex.getMessage(),"Экспорт MP3",JOptionPane.ERROR_MESSAGE);return;}
                selectionInfo.setText("Сохранено: "+target.getFileName());
                if(!closed){
                    int choice=JOptionPane.showOptionDialog(SourceContext.this,"MP3 сохранён:\n"+target.toAbsolutePath(),"Экспорт завершён",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,new String[]{"Открыть папку","Закрыть"},"Закрыть");
                    if(choice==0)try{Desktop.getDesktop().open(target.toAbsolutePath().getParent().toFile());}
                    catch(Exception ex){JOptionPane.showMessageDialog(SourceContext.this,"MP3 сохранён, но открыть папку не удалось:\n"+target.toAbsolutePath().getParent()+"\n"+ex.getMessage(),"Открытие папки",JOptionPane.ERROR_MESSAGE);}
                }
            }
        }.execute();
    }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Экспорт MP3",JOptionPane.ERROR_MESSAGE);}}
    private void paintSelection(Graphics g,int width,int height){if(sound==null||!Double.isFinite(selectionStart))return;int a=(int)((selectionStart-offset)/sound.duration*width),b=(int)((selectionEnd-offset)/sound.duration*width);g.setColor(new Color(255,170,0,65));g.fillRect(a,0,b-a,height);g.setColor(new Color(180,100,0));g.drawLine(a,0,a,height);g.drawLine(b,0,b,height);}

    static final class Span {
        String uid,status;double start,end,keptStart,keptEnd;boolean current;
        boolean overlaps(double a,double b){return start<b&&end>a;}
        String label(){String id=uid.replaceFirst("^[^_]+_","");int copy=id.indexOf("_copy_");if(copy>=0)id=id.substring(0,copy)+" копия "+id.substring(copy+6,Math.min(id.length(),copy+12));return (current?"ТЕКУЩИЙ ":"")+id+" · "+(status.equals("excluded")?"исключён":status.equals("reviewed")?"проверен":status.equals("draft")?"черновик":"не проверен");}
    }
    static List<Span> readSpans(Path report,List<ReviewData.Item> items,ReviewData.Item current,double duration,double from,double to,String state)throws Exception {
        List<Span> result=new ArrayList<>();
        for(ReviewData.Item item:items){
            if(!item.source.equals(current.source))continue;
            Span s=new Span();s.uid=item.uid;s.start=item.sourceStart;s.end=((Number)item.raw.get("end")).doubleValue();s.keptStart=s.start;s.keptEnd=s.end;s.status=item.status;s.current=item.uid.equals(current.uid);
            if(!s.overlaps(Math.max(0,current.sourceStart-30),current.sourceStart+duration+30))continue;
            if(s.current){s.end=s.start+duration;s.keptStart=s.start+from;s.keptEnd=s.start+to;s.status=state;}
            else {
                Path saved=report.resolve("desktop_edits").resolve(item.uid+".json");
                if(Files.isRegularFile(saved)){
                    Map<String,Object> edit=Json.object(Json.parse(Files.readString(saved)));
                    if(item.original.equals(edit.get("original_text"))){
                        s.status=String.valueOf(edit.getOrDefault("status","draft"));
                        if(edit.get("trim_start") instanceof Number)s.keptStart=s.start+((Number)edit.get("trim_start")).doubleValue();
                        if(edit.get("trim_end") instanceof Number)s.keptEnd=s.start+((Number)edit.get("trim_end")).doubleValue();
                    }
                }
            }
            result.add(s);
        }
        return result;
    }
    void setSpans(List<Span> values){spans=new ArrayList<>(values);updateLanes();}
    private void updateLanes(){visibleSpans=new ArrayList<>();if(sound!=null)for(Span s:spans)if(s.overlaps(offset,offset+sound.duration))visibleSpans.add(s);lanes.setPreferredSize(new Dimension(820,Math.max(36,visibleSpans.size()*36)));lanes.revalidate();lanes.repaint();}

    static Path findSource(Path root,String name)throws Exception {
        Path path=Paths.get(name);if(!path.isAbsolute())path=root.resolve(path);
        if(!Files.isRegularFile(path))throw new java.io.IOException("Исходный MP3 не найден: "+path);
        return path;
    }
    static double windowStart(double sourceStart,double margin){return Math.max(0,sourceStart-margin);}
    static ReviewData.Sound decode(Path root,Path source,double start,double length)throws Exception {
        Path binary=ffmpeg(root);
        Path wav=Files.createTempFile("akiat-context-",".wav"),log=Files.createTempFile("akiat-context-",".log");
        Process process=null;
        try {
            process=new ProcessBuilder(binary.toString(),"-nostdin","-v","error","-threads","1","-y","-ss",Double.toString(start),"-i",source.toString(),"-t",Double.toString(length),"-vn","-ac","1","-c:a","pcm_s16le",wav.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(45,TimeUnit.SECONDS))throw new java.io.IOException("Истекло время чтения исходного MP3");
            if(process.exitValue()!=0)throw new java.io.IOException("Не удалось прочитать MP3: "+Files.readString(log));
            return ReviewData.readSound(wav);
        } finally {
            if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor();}
            Files.deleteIfExists(wav);Files.deleteIfExists(log);
        }
    }
    SourceContext(JFrame owner,Path root,ReviewData.Item item,double duration,double from,double to,double cursor)throws Exception {
        super(owner,"Контекст из MP3 · "+item.uid,true);
        this.root=root;source=findSource(root,item.source);sourceStart=item.sourceStart;this.duration=duration;trimStart=from;trimEnd=to;initialCursor=cursor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        JPanel header=new JPanel(new GridLayout(0,1));header.add(new JLabel(source.getFileName().toString()));header.add(info);
        header.add(new JLabel("Зелёный — оставленный участок; красный — обрезанные края; серый — соседний звук."));
        header.add(new JLabel("Прослушивание и экспорт: текст, границы и статус проверки не меняются."));panel.add(header,BorderLayout.NORTH);
        JPanel middle=new JPanel(new BorderLayout());timeline.setPreferredSize(new Dimension(820,150));middle.add(timeline,BorderLayout.CENTER);middle.add(slider,BorderLayout.SOUTH);
        JScrollPane laneScroll=new JScrollPane(lanes,ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);laneScroll.setPreferredSize(new Dimension(840,155));
        laneScroll.setBorder(BorderFactory.createEmptyBorder());middle.setBorder(BorderFactory.createEmptyBorder(0,0,0,laneScroll.getVerticalScrollBar().getPreferredSize().width));
        JPanel tracks=new JPanel(new BorderLayout());tracks.add(middle,BorderLayout.NORTH);tracks.add(laneScroll,BorderLayout.CENTER);tracks.setBorder(BorderFactory.createTitledBorder("Соседние фрагменты — голубые; исключённые — серые. Каждая копия на отдельной строке."));panel.add(tracks,BorderLayout.CENTER);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton(controls,"▶ / Пауза",()->{previewEnd=Double.POSITIVE_INFINITY;if(clip!=null){if(clip.isRunning())clip.stop();else{if(position()>=sound.duration-.02)seek(0);clip.start();}}});
        addButton(controls,"К началу обрезки",()->seek(sourceStart+trimStart-offset));addButton(controls,"К концу обрезки",()->seek(sourceStart+trimEnd-offset));
        controls.add(new JLabel("Контекст ±с:"));controls.add(margin);speeds.setSelectedIndex(2);controls.add(speeds);controls.add(clock);
        JPanel exportPanel=new JPanel(new FlowLayout(FlowLayout.LEFT));exportPanel.add(selectMode);exportPanel.add(new JLabel("От, с исходника:"));exportPanel.add(selectionFrom);exportPanel.add(new JLabel("до:"));exportPanel.add(selectionTo);
        addButton(exportPanel,"▶ Выделение",()->{try{validateSelection();seek(selectionStart-offset);previewEnd=selectionEnd-offset;clip.start();}catch(Exception ex){selectionInfo.setText(ex.getMessage());}});addButton(exportPanel,"Экспорт MP3…",()->exportSelection());
        javax.swing.event.ChangeListener rangeChanged=e->{if(!selectingRange){selectionStart=((Number)selectionFrom.getValue()).doubleValue();selectionEnd=((Number)selectionTo.getValue()).doubleValue();timeline.repaint();lanes.repaint();}};selectionFrom.addChangeListener(rangeChanged);selectionTo.addChangeListener(rangeChanged);
        JPanel footer=new JPanel(new BorderLayout());JPanel footerRows=new JPanel(new GridLayout(0,1));footerRows.add(controls);footerRows.add(exportPanel);footerRows.add(selectionInfo);footer.add(footerRows,BorderLayout.CENTER);JButton close=new JButton("Вернуться к правкам");close.addActionListener(e->dispose());footer.add(close,BorderLayout.SOUTH);panel.add(footer,BorderLayout.SOUTH);setContentPane(panel);
        slider.addChangeListener(e->{if(!updating&&sound!=null)seek(slider.getValue()/10000.0*sound.duration);});
        installMouse(timeline);
        margin.addActionListener(e->load());speeds.addActionListener(e->{double at=position();boolean playing=clip!=null&&clip.isRunning();speed=new double[]{.5,.75,1}[speeds.getSelectedIndex()];try{openAudio();seek(at);if(playing&&clip!=null)clip.start();}catch(Exception ex){info.setText(ex.getMessage());}});
        addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed=true;generation++;timer.stop();closeAudio();}});
        pack();setLocationRelativeTo(owner);timer.start();load();
    }
    private void addButton(JPanel panel,String text,Runnable action){JButton b=new JButton(text);b.addActionListener(e->action.run());panel.add(b);}
    private void closeAudio(){if(clip!=null){clip.stop();clip.close();clip=null;}}
    private void openAudio()throws Exception{closeAudio();if(sound==null)return;byte[] bytes=ReviewData.playbackBytes(sound,speed);clip=AudioSystem.getClip();clip.open(sound.format,bytes,0,bytes.length);}
    private double position(){return clip==null?0:clip.getMicrosecondPosition()/1e6*speed;}
    private void seek(double at){if(clip!=null)clip.setMicrosecondPosition((long)(Math.max(0,Math.min(sound.duration,at))/speed*1e6));refresh();}
    private void load(){
        int version=++generation;closeAudio();sound=null;previewEnd=Double.POSITIVE_INFINITY;timeline.repaint();info.setText("Читаю контекст из исходного MP3…");
        double padding=(Integer)margin.getSelectedItem(),begin=windowStart(sourceStart,padding),length=sourceStart+duration+padding-begin;
        new SwingWorker<ReviewData.Sound,Void>(){
            protected ReviewData.Sound doInBackground()throws Exception{return decode(root,source,begin,length);}
            protected void done(){if(closed||version!=generation)return;try{sound=get();offset=begin;updateLanes();openAudio();seek(sourceStart+initialCursor-offset);info.setText("Исходник: "+time(offset)+" — "+time(offset+sound.duration)+" · оставлено "+time(sourceStart+trimStart)+" — "+time(sourceStart+trimEnd));}catch(Exception ex){info.setText("Ошибка: "+ex.getMessage());}}
        }.execute();
    }
    static String time(double seconds){return String.format(Locale.ROOT,"%02d:%06.3f",(int)(seconds/60),seconds%60);}
    private void refresh(){if(sound==null)return;if(clip!=null&&clip.isRunning()&&position()>=previewEnd){clip.stop();previewEnd=Double.POSITIVE_INFINITY;}updating=true;slider.setValue((int)(position()/sound.duration*10000));updating=false;clock.setText(time(offset+position()));timeline.repaint();lanes.repaint();}
    private final class Lanes extends JPanel {
        Lanes(){setToolTipText("");installMouse(this);}
        private int x(double t){return (int)((t-offset)/sound.duration*getWidth());}
        public String getToolTipText(MouseEvent e){int row=e.getY()/36;if(row>=visibleSpans.size())return null;Span s=visibleSpans.get(row);return s.uid+" · "+s.label()+" · исходный "+time(s.start)+"–"+time(s.end)+" · оставлено "+time(s.keptStart)+"–"+time(s.keptEnd);}
        protected void paintComponent(Graphics g){super.paintComponent(g);if(sound==null)return;
            for(int i=0;i<visibleSpans.size();i++){Span s=visibleSpans.get(i);int y=i*36,a=Math.max(0,x(s.start)),b=Math.min(getWidth(),x(s.end));
                g.setColor(new Color(250,225,225));g.fillRect(a,y+2,b-a,32);
                g.setColor(s.status.equals("excluded")?new Color(200,200,200):s.current?new Color(185,235,190):new Color(190,220,250));
                int left=Math.max(a,x(s.keptStart)),right=Math.min(b,x(s.keptEnd));g.fillRect(left,y+2,Math.max(0,right-left),32);
                g.setColor(s.current?new Color(15,110,30):new Color(65,85,110));g.drawRect(a,y+2,Math.max(0,b-a-1),31);
                Graphics text=g.create(a+2,y+3,Math.max(0,b-a-4),29);text.setFont(getFont().deriveFont(s.current?Font.BOLD:Font.PLAIN,11f));text.drawString(s.label(),1,12);text.drawString(time(s.keptStart)+"–"+time(s.keptEnd),1,25);text.dispose();
            }
            paintSelection(g,getWidth(),getHeight());g.setColor(Color.BLUE);int cursorX=x(offset+position());g.drawLine(cursorX,0,cursorX,getHeight());
        }
    }
    private final class Timeline extends JPanel {
        private int x(double absolute){return (int)((absolute-offset)/sound.duration*getWidth());}
        protected void paintComponent(Graphics g){super.paintComponent(g);if(sound==null)return;int h=getHeight(),w=getWidth();
            g.setColor(new Color(235,235,235));g.fillRect(0,0,w,h);
            for(Span s:visibleSpans)if(!s.current){g.setColor(s.status.equals("excluded")?new Color(215,215,215):new Color(215,232,250));g.fillRect(x(s.keptStart),0,x(s.keptEnd)-x(s.keptStart),h);}
            g.setColor(new Color(255,215,215));g.fillRect(x(sourceStart),0,x(sourceStart+duration)-x(sourceStart),h);
            g.setColor(new Color(205,240,210));g.fillRect(x(sourceStart+trimStart),0,x(sourceStart+trimEnd)-x(sourceStart+trimStart),h);
            g.setColor(new Color(75,85,100));for(int i=0;i<w;i++){int a=(int)((long)i*sound.peaks.length/w),b=Math.max(a+1,(int)((long)(i+1)*sound.peaks.length/w));float peak=0;for(int j=a;j<Math.min(b,sound.peaks.length);j++)peak=Math.max(peak,sound.peaks[j]);int amplitude=(int)(peak*(h/2-22));g.drawLine(i,h/2-amplitude,i,h/2+amplitude);}
            g.setColor(new Color(20,100,35));for(double t:new double[]{sourceStart+trimStart,sourceStart+trimEnd}){int px=x(t);g.drawLine(px,0,px,h);g.drawString(time(t),Math.max(0,Math.min(w-85,px+3)),16);}
            paintSelection(g,w,h);g.setColor(Color.BLUE);int px=x(offset+position());g.drawLine(px,0,px,h);
        }
    }
}
