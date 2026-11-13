package akiat.review;

import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

final class EqualizerDialog extends JDialog {
    private final Path report;
    private final ReviewData.Item current;
    private final ReviewData.Sound original;
    private ReviewData.Sound filtered;
    private final double from,to,nyquist;
    private final JSpinner low,high;
    private final JSlider gain=new JSlider(-60,0,-12),position=new JSlider(0,10000);
    private final JLabel info=new JLabel("Строю спектрограмму…"),gainLabel=new JLabel("−12 дБ");
    private final JList<ReviewData.Item> targets;
    private final Plot spectrogram=new Plot(true),spectrum=new Plot(false);
    private float[][] before;
    private float[] beforeAverage,afterAverage;
    private BufferedImage pixels;
    private Clip audio;
    private double cursor;
    private boolean updating,closed,busy=true;
    private int generation;
    private EqualizerData.Settings ready;
    private final javax.swing.Timer delay=new javax.swing.Timer(250,e->updatePreview());
    private final javax.swing.Timer tick=new javax.swing.Timer(60,e->tick());

    EqualizerDialog(JFrame owner,Path report,List<ReviewData.Item> items,ReviewData.Item current,ReviewData.Sound sound,double from,double to)throws Exception{
        super(owner,"Ручной эквалайзер · "+current.uid,true);this.report=report;this.current=current;original=sound;this.from=from;this.to=to;cursor=from;nyquist=sound.format.getSampleRate()/2.;
        EqualizerData.Settings saved=EqualizerData.applied(report,current);double initialLow=saved==null?100:saved.low,initialHigh=saved==null?Math.min(500,nyquist):saved.high;gain.setValue(saved==null?-12:(int)saved.db);
        low=new JSpinner(new SpinnerNumberModel(initialLow,0.,nyquist,10.));high=new JSpinner(new SpinnerNumberModel(initialHigh,0.,nyquist,10.));
        targets=new JList<>(items.toArray(new ReviewData.Item[0]));targets.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);targets.setSelectedValue(current,true);
        targets.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){JLabel label=(JLabel)super.getListCellRendererComponent(l,value,index,selected,focus);ReviewData.Item item=(ReviewData.Item)value;String state=item.status.equals("reviewed")?"проверен":item.status.equals("excluded")?"исключён":item.status.equals("draft")?"черновик":"не проверен";label.setText(item.uid.replaceFirst("^[^_]+_","")+" · "+state);label.setToolTipText(item.uid);return label;}});
        JPanel content=new JPanel(new BorderLayout(8,8));content.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JPanel header=new JPanel(new GridLayout(0,1));header.add(new JLabel("Спектр оставленного участка "+SourceContext.time(from)+" — "+SourceContext.time(to)+". Музыка и голос показаны вместе."));header.add(new JLabel("Выделите частоты: вертикально на спектрограмме или горизонтально на спектре. Жёлтый — подавляемый диапазон."));content.add(header,BorderLayout.NORTH);
        JPanel plots=new JPanel(new GridLayout(2,1,0,8));spectrogram.setPreferredSize(new Dimension(740,230));spectrum.setPreferredSize(new Dimension(740,190));plots.add(spectrogram);plots.add(spectrum);
        JPanel group=new JPanel(new BorderLayout());group.setBorder(BorderFactory.createTitledBorder("Кому применить настройки (Ctrl / Shift)"));JScrollPane scroll=new JScrollPane(targets);scroll.setPreferredSize(new Dimension(280,300));group.add(scroll,BorderLayout.CENTER);
        JPanel groupButtons=new JPanel(new GridLayout(0,1));add(groupButtons,"Только текущему",()->targets.setSelectedValue(current,true));add(groupButtons,"Все подтверждённые",()->selectReviewed());add(groupButtons,"Набор из снимка обучения…",()->selectSnapshot());group.add(groupButtons,BorderLayout.SOUTH);
        JPanel middle=new JPanel(new BorderLayout(8,0));middle.add(plots,BorderLayout.CENTER);middle.add(group,BorderLayout.EAST);content.add(middle,BorderLayout.CENTER);
        JPanel footer=new JPanel(new GridLayout(0,1));JPanel bands=new JPanel(new FlowLayout(FlowLayout.LEFT));bands.add(new JLabel("От, Гц:"));bands.add(low);bands.add(new JLabel("до, Гц:"));bands.add(high);bands.add(new JLabel("Подавление:"));gain.setPreferredSize(new Dimension(220,35));bands.add(gain);bands.add(gainLabel);add(bands,"Без подавления (0 дБ)",()->gain.setValue(0));footer.add(bands);
        JPanel playback=new JPanel(new FlowLayout(FlowLayout.LEFT));add(playback,"▶ До",()->play(false));add(playback,"▶ После",()->play(true));add(playback,"Пауза",()->{if(audio!=null)audio.stop();});add(playback,"С начала",()->{cursor=from;if(audio!=null)audio.setMicrosecondPosition((long)(from*1e6));});playback.add(new JLabel("До — голубой спектр; После — зелёный. Без изменения громкости для сравнения."));footer.add(playback);footer.add(position);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));add(actions,"Применить настройки к выбранным",()->apply());add(actions,"Отменить эквалайзер у выбранных",()->disableSelected());add(actions,"Закрыть",()->dispose());footer.add(actions);footer.add(info);content.add(footer,BorderLayout.SOUTH);setContentPane(content);
        delay.setRepeats(false);low.addChangeListener(e->changed());high.addChangeListener(e->changed());gain.addChangeListener(e->changed());position.addChangeListener(e->{if(!updating){cursor=from+(to-from)*position.getValue()/10000.;if(audio!=null)audio.setMicrosecondPosition((long)(cursor*1e6));}});
        addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed=true;generation++;delay.stop();tick.stop();closeAudio();}});setDefaultCloseOperation(DISPOSE_ON_CLOSE);pack();Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();setSize(Math.min(getWidth(),screen.width),Math.min(getHeight(),screen.height));setLocationRelativeTo(owner);tick.start();updatePreview();
    }
    private void add(JPanel panel,String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->action.run());panel.add(button);}
    private EqualizerData.Settings settings(){return new EqualizerData.Settings(((Number)low.getValue()).doubleValue(),((Number)high.getValue()).doubleValue(),gain.getValue());}
    private void changed(){if(updating)return;busy=true;generation++;gainLabel.setText(gain.getValue()+" дБ");closeAudio();spectrogram.repaint();spectrum.repaint();delay.restart();}
    private void updatePreview(){int version=++generation;EqualizerData.Settings s=settings();try{s.validate(original.format.getSampleRate());}catch(Exception ex){info.setText(ex.getMessage());busy=true;return;}busy=true;info.setText("Пересчитываю ручной фильтр…");
        new SwingWorker<ReviewData.Sound,Void>(){float[][] analysis;float[] average;
            protected ReviewData.Sound doInBackground(){analysis=before==null?EqualizerData.spectrum(original,from,to):before;ReviewData.Sound result=EqualizerData.filter(original,s);average=average(EqualizerData.spectrum(result,from,to));return result;}
            protected void done(){if(closed||version!=generation)return;try{filtered=get();before=analysis;if(beforeAverage==null)beforeAverage=average(before);afterAverage=average;ready=s;busy=false;buildImage();info.setText(String.format(Locale.ROOT,"%.0f–%.0f Гц · %.0f дБ. Предпросмотр готов; применяйте настройки отдельной кнопкой.",s.low,s.high,s.db));spectrogram.repaint();spectrum.repaint();}catch(Exception ex){info.setText("Ошибка: "+ex.getMessage());}}
        }.execute();
    }
    private static float[] average(float[][] columns){float[] result=new float[columns[0].length];for(int b=0;b<result.length;b++){double sum=0;for(float[] col:columns)sum+=Math.pow(10,col[b]/10.);result[b]=(float)(10*Math.log10(Math.max(1e-14,sum/columns.length)));}return result;}
    private double frequency(double fraction){return 20*Math.pow(nyquist/20,Math.max(0,Math.min(1,fraction)));}
    private double fraction(double hz){return Math.max(0,Math.min(1,Math.log(Math.max(20,hz)/20)/Math.log(nyquist/20)));}
    private void buildImage(){pixels=new BufferedImage(before.length,256,BufferedImage.TYPE_INT_RGB);for(int x=0;x<pixels.getWidth();x++)for(int y=0;y<256;y++){double hz=frequency(1-y/255.);int bin=Math.min(before[x].length-1,(int)Math.round(hz*EqualizerData.SIZE/original.format.getSampleRate()));float level=Math.max(0,Math.min(1,(before[x][bin]+95)/75));pixels.setRGB(x,y,Color.HSBtoRGB(.75f-.75f*level,1,Math.max(.05f,level)));}}
    private void play(boolean after){if(after&&(busy||filtered==null)){info.setText("Дождитесь готовности предпросмотра.");return;}try{if(audio!=null)cursor=audio.getMicrosecondPosition()/1e6;closeAudio();if(cursor<from||cursor>=to-.02)cursor=from;ReviewData.Sound s=after?filtered:original;audio=AudioSystem.getClip();audio.open(s.format,s.bytes,0,s.bytes.length);audio.setMicrosecondPosition((long)(cursor*1e6));audio.start();}catch(Exception ex){info.setText(ex.getMessage());}}
    private void closeAudio(){if(audio!=null){cursor=audio.getMicrosecondPosition()/1e6;audio.stop();audio.close();audio=null;}}
    private void tick(){if(audio!=null){cursor=audio.getMicrosecondPosition()/1e6;if(cursor>=to)audio.stop();updating=true;position.setValue((int)((cursor-from)/(to-from)*10000));updating=false;spectrogram.repaint();}}
    private void selectReviewed(){try{List<Integer> ids=new ArrayList<>();for(int i=0;i<targets.getModel().getSize();i++){ReviewData.Item item=targets.getModel().getElementAt(i);Path p=report.resolve("desktop_edits").resolve(item.uid+".json");if(Files.exists(p)){Map<String,Object> m=EqualizerData.read(p);if("reviewed".equals(m.get("status"))&&Boolean.TRUE.equals(m.get("training_eligible")))ids.add(i);}}targets.setSelectedIndices(ids.stream().mapToInt(Integer::intValue).toArray());info.setText("Выбрано: "+ids.size()+". Спектр и прослушивание — для текущего фрагмента.");}catch(Exception ex){info.setText(ex.getMessage());}}
    private void selectSnapshot(){try{JFileChooser chooser=new JFileChooser(Paths.get("outputs").toFile());chooser.setDialogTitle("manifest.json нужного обучения");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Set<String> uids=new HashSet<>();for(Object r:Json.array(EqualizerData.read(chooser.getSelectedFile().toPath()).get("records")))uids.add((String)Json.object(r).get("uid"));List<Integer> ids=new ArrayList<>();for(int i=0;i<targets.getModel().getSize();i++)if(uids.contains(targets.getModel().getElementAt(i).uid))ids.add(i);if(ids.size()!=uids.size()||ids.isEmpty())throw new IllegalArgumentException("Не все фрагменты снимка найдены");targets.setSelectedIndices(ids.stream().mapToInt(Integer::intValue).toArray());info.setText("Выбран набор из снимка: "+ids.size()+". Спектр относится к текущему фрагменту.");}catch(Exception ex){info.setText(ex.getMessage());}}
    private void apply(){try{low.commitEdit();high.commitEdit();if(busy||ready==null)throw new IllegalStateException("Дождитесь готовности предпросмотра");List<ReviewData.Item> selected=targets.getSelectedValuesList();if(selected.isEmpty())return;
        for(ReviewData.Item item:selected)ready.validate(ReviewData.readSound(item.audio).format.getSampleRate());
        if(selected.size()>1&&JOptionPane.showConfirmDialog(this,"Применить диапазон "+ready.low+"–"+ready.high+" Гц, "+ready.db+" дБ к "+selected.size()+" фрагментам?\nСпектр и прослушивание здесь относятся только к "+current.uid,"Применить настройки группе",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        for(ReviewData.Item item:selected)EqualizerData.apply(report,item,ready);info.setText("Настройки применены: "+selected.size()+". Сохранены только параметры; оригиналы не изменены.");
    }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Эквалайзер",JOptionPane.ERROR_MESSAGE);}}
    private void disableSelected(){try{for(ReviewData.Item item:targets.getSelectedValuesList())EqualizerData.disable(report,item);info.setText("Применение отменено. Основной плеер и следующее обучение используют оригинал.");}catch(Exception ex){info.setText(ex.getMessage());}}
    private final class Plot extends JPanel {
        final boolean image;double anchor;
        Plot(boolean image){this.image=image;setToolTipText("");MouseAdapter mouse=new MouseAdapter(){private double hz(MouseEvent e){return frequency(image?1-(e.getY()-20.)/Math.max(1,getHeight()-50):(e.getX()-55.)/Math.max(1,getWidth()-75));}public void mousePressed(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e))anchor=hz(e);}public void mouseDragged(MouseEvent e){if((e.getModifiersEx()&InputEvent.BUTTON1_DOWN_MASK)==0)return;double other=hz(e),a=Math.min(anchor,other),b=Math.max(anchor,other);if(b-a<1)return;updating=true;low.setValue(Math.floor(a));high.setValue(Math.min(nyquist,Math.ceil(b)));updating=false;changed();}};addMouseListener(mouse);addMouseMotionListener(mouse);}
        public String getToolTipText(MouseEvent e){double hz=frequency(image?1-(e.getY()-20.)/Math.max(1,getHeight()-50):(e.getX()-55.)/Math.max(1,getWidth()-75));return String.format(Locale.ROOT,"%.0f Гц",hz);}
        protected void paintComponent(Graphics g){super.paintComponent(g);int left=55,top=20,w=getWidth()-75,h=getHeight()-50;if(w<=0||h<=0)return;g.setColor(new Color(20,23,30));g.fillRect(left,top,w,h);EqualizerData.Settings s=settings();
            if(image&&pixels!=null)g.drawImage(pixels,left,top,w,h,null);
            if(!image&&beforeAverage!=null){drawCurve(g,beforeAverage,new Color(70,190,250),left,top,w,h);if(afterAverage!=null)drawCurve(g,afterAverage,new Color(90,240,120),left,top,w,h);}
            g.setColor(new Color(255,210,0,65));if(image){int a=top+(int)((1-fraction(s.high))*h),b=top+(int)((1-fraction(s.low))*h);g.fillRect(left,a,w,b-a);g.setColor(Color.YELLOW);g.drawLine(left,a,left+w,a);g.drawLine(left,b,left+w,b);}else{int a=left+(int)(fraction(s.low)*w),b=left+(int)(fraction(s.high)*w);g.fillRect(a,top,b-a,h);g.setColor(Color.YELLOW);g.drawLine(a,top,a,top+h);g.drawLine(b,top,b,top+h);}
            g.setColor(Color.GRAY);for(double hz:new double[]{20,50,100,200,500,1000,2000,5000,10000})if(hz<=nyquist){if(image){int y=top+(int)((1-fraction(hz))*h);g.drawString(Integer.toString((int)hz),2,y+4);}else{int x=left+(int)(fraction(hz)*w);g.drawString(Integer.toString((int)hz),x-10,top+h+16);}}
            g.setColor(Color.DARK_GRAY);g.drawString(image?"Гц · спектрограмма исходника (цвет: −95…−20 дБ)":"Средний спектр: уровень от −100 до 0 дБ",left,14);
            if(image){g.drawString(SourceContext.time(from),left,top+h+16);g.drawString(SourceContext.time(to),left+w-70,top+h+16);g.setColor(Color.WHITE);int x=left+(int)((cursor-from)/(to-from)*w);g.drawLine(x,top,x,top+h);}else for(int db=-100;db<=0;db+=25)g.drawString(Integer.toString(db),15,top+(int)(-db/100.*h)+4);
        }
        private void drawCurve(Graphics g,float[] avg,Color color,int left,int top,int w,int h){g.setColor(color);int previous=0;for(int x=0;x<w;x++){double bin=frequency(x/(double)w)*EqualizerData.SIZE/original.format.getSampleRate();int i=Math.min(avg.length-1,(int)bin);double value=avg[i];if(i+1<avg.length)value+=(avg[i+1]-value)*(bin-i);int y=top+(int)(Math.max(0,Math.min(100,-value))/100*h);if(x>0)g.drawLine(left+x-1,previous,left+x,y);previous=y;}}
    }
}
