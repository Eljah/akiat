package akiat.review;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.DefaultHighlighter;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class ReviewApp extends JFrame {
    private Path report;
    private List<ReviewData.Item> items=new ArrayList<>();
    private final DefaultListModel<ReviewData.Item> listModel=new DefaultListModel<>();
    private final JList<ReviewData.Item> clips=new JList<>(listModel);
    private final JTextArea original=new JTextArea(3,50), editor=new JTextArea(4,50);
    private final JLabel source=new JLabel("Выберите отчёт"), status=new JLabel(" "), position=new JLabel("0.00 / 0.00 с");
    private final JSlider slider=new JSlider(0,10000,0);
    private final JSpinner trimFrom=new JSpinner(new SpinnerNumberModel(0.0,0.0,3600.0,.05));
    private final JSpinner trimTo=new JSpinner(new SpinnerNumberModel(0.0,0.0,3600.0,.05));
    private final JLabel trimInfo=new JLabel(" ");
    private double trimStart,trimEnd;
    private final WordTable tableModel=new WordTable(); private final JTable table=new JTable(tableModel);
    private final Wave wave=new Wave();
    private ReviewData.Item current;
    private ReviewData.Sound sound;
    private List<ReviewData.Word> words=new ArrayList<>();
    private Clip audio;
    private double cursor,stopAt=Double.POSITIVE_INFINITY;
    private boolean dirty,loading,updating,selecting;
    private String reviewStatus="draft";
    private int selected=-1,loadVersion=0;
    private final javax.swing.Timer tick=new javax.swing.Timer(40,e->tick());
    private final javax.swing.Timer editDelay=new javax.swing.Timer(350,e->rebuild());

    ReviewApp(Path initial)throws Exception{
        super("Akiat — проверка и обрезка корпуса");setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1280,920);setLocationRelativeTo(null);editDelay.setRepeats(false);
        Font font=new Font("Segoe UI",Font.PLAIN,16);editor.setFont(font);original.setFont(font);original.setEditable(false);
        editor.setLineWrap(true);editor.setWrapStyleWord(true);original.setLineWrap(true);original.setWrapStyleWord(true);
        table.setRowHeight(27);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);clips.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.add(button("Открыть отчёт…",()->chooseReport()));
        JTextField search=new JTextField(26);top.add(search);top.add(button("Найти",()->filter(search.getText())));top.add(button("Все",()->{search.setText("");filter("");}));
        top.add(button("Папка исправлений",()->{try{Files.createDirectories(report.resolve("desktop_edits"));Desktop.getDesktop().open(report.resolve("desktop_edits").toFile());}catch(Exception e){error(e);}}));
        JPanel right=new JPanel(new BorderLayout(5,5));JPanel center=new JPanel();center.setLayout(new BoxLayout(center,BoxLayout.Y_AXIS));
        center.add(source);center.add(new JLabel("Исходная расшифровка (пометки относятся к ней):"));center.add(new JScrollPane(original));
        center.add(new JLabel("Исправленный текст — редактируйте здесь. Изменение текста пересчитывает приблизительные метки:"));center.add(new JScrollPane(editor));
        center.add(new JLabel("≈ Приблизительные позиции по длине слов и активности звука. Это НЕ распознанные границы слов."));
        wave.setPreferredSize(new Dimension(850,185));wave.setMinimumSize(new Dimension(100,185));center.add(wave);center.add(slider);
        JPanel playback=new JPanel(new FlowLayout(FlowLayout.LEFT));playback.add(button("▶ / Пауза",()->toggle()));playback.add(button("−0,5 с",()->seek(cursor-.5)));playback.add(button("+0,5 с",()->seek(cursor+.5)));
        playback.add(button("Слово ±0,5 с",()->playWord()));playback.add(position);center.add(playback);
        JPanel trim=new JPanel(new FlowLayout(FlowLayout.LEFT));trim.add(new JLabel("Оставить, с:"));trim.add(trimFrom);trim.add(new JLabel("—"));trim.add(trimTo);
        trim.add(button("Начало = курсор",()->setTrim(cursor,trimEnd)));trim.add(button("Конец = курсор",()->setTrim(trimStart,cursor)));
        trim.add(button("Применить границы",()->{try{trimFrom.commitEdit();trimTo.commitEdit();setTrim(((Number)trimFrom.getValue()).doubleValue(),((Number)trimTo.getValue()).doubleValue());}catch(Exception e){error(e);}}));
        trim.add(button("Сброс обрезки",()->{if(sound!=null)setTrim(0,sound.duration);}));center.add(trim);center.add(trimInfo);
        JPanel timing=new JPanel(new FlowLayout(FlowLayout.LEFT));timing.add(button("Начало слова = курсор",()->manual(cursor)));timing.add(button("Импорт меток…",()->importTimes()));
        timing.add(new JLabel("Перетаскивайте подписи слов или меняйте начало в таблице."));center.add(timing);
        JScrollPane wordScroll=new JScrollPane(table);wordScroll.setPreferredSize(new Dimension(850,220));center.add(wordScroll);
        right.add(center,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new FlowLayout(FlowLayout.LEFT));bottom.add(button("Сохранить черновик",()->save("draft")));
        bottom.add(button("Проверено → следующий",()->{if(save("reviewed"))next();}));bottom.add(button("Исключить → следующий",()->{if(save("excluded"))next();}));bottom.add(button("Следующий",()->next()));
        JPanel footer=new JPanel(new BorderLayout());footer.add(bottom,BorderLayout.NORTH);footer.add(status,BorderLayout.SOUTH);right.add(footer,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(clips),right);split.setDividerLocation(300);add(top,BorderLayout.NORTH);add(split,BorderLayout.CENTER);
        clips.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!selecting){ReviewData.Item item=clips.getSelectedValue();if(item!=null&&item!=current){if(confirmLeave())load(item);else{selecting=true;clips.setSelectedValue(current,true);selecting=false;}}}});
        table.getSelectionModel().addListSelectionListener(e->{if(!updating&&!e.getValueIsAdjusting()){selected=table.getSelectedRow();if(selected>=0&&selected<words.size())seek(words.get(selected).start);highlight();wave.repaint();}});
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)playWord();}});
        editor.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int caret=editor.getCaretPosition();for(int j=0;j<words.size();j++)if(caret>=words.get(j).from&&caret<=words.get(j).to){select(j);if(e.getClickCount()==2)playWord();break;}}});
        editor.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}});
        slider.addChangeListener(e->{if(!updating&&sound!=null)seek(slider.getValue()/10000.0*sound.duration);});
        DocumentListener trimChanged=new DocumentListener(){private void change(){if(!loading&&!updating&&sound!=null){dirty=true;reviewStatus="draft";}}public void insertUpdate(DocumentEvent e){change();}public void removeUpdate(DocumentEvent e){change();}public void changedUpdate(DocumentEvent e){change();}};
        ((JSpinner.DefaultEditor)trimFrom.getEditor()).getTextField().getDocument().addDocumentListener(trimChanged);
        ((JSpinner.DefaultEditor)trimTo.getEditor()).getTextField().getDocument().addDocumentListener(trimChanged);
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(confirmLeave()){loadVersion++;closeAudio();tick.stop();dispose();}}});
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,InputEvent.CTRL_DOWN_MASK),"play");
        getRootPane().getActionMap().put("play",new AbstractAction(){public void actionPerformed(ActionEvent e){toggle();}});
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S,InputEvent.CTRL_DOWN_MASK),"save");
        getRootPane().getActionMap().put("save",new AbstractAction(){public void actionPerformed(ActionEvent e){save("draft");}});
        tick.start();if(initial!=null)openReport(initial);
    }
    private JButton button(String text,Runnable action){JButton b=new JButton(text);b.addActionListener(e->action.run());return b;}
    private void error(Exception e){status.setText("Ошибка: "+e.getMessage());JOptionPane.showMessageDialog(this,e.getMessage(),"Akiat",JOptionPane.ERROR_MESSAGE);}
    private void chooseReport(){if(!confirmLeave())return;JFileChooser c=new JFileChooser(report==null?".":report.toString());c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)try{openReport(c.getSelectedFile().toPath());}catch(Exception e){error(e);}}
    private void openReport(Path path)throws Exception{List<ReviewData.Item> loaded=ReviewData.readReport(path);closeAudio();report=path.toAbsolutePath();items=loaded;current=null;dirty=false;filter("");setTitle("Akiat · Обрезка — "+report.getFileName()+" · "+items.size()+" подозрительных фрагментов");}
    private void filter(String text){if(!confirmLeave())return;selecting=true;listModel.clear();String q=text.toLowerCase(Locale.ROOT);for(ReviewData.Item i:items)if((i.uid+" "+i.source+" "+i.original).toLowerCase(Locale.ROOT).contains(q))listModel.addElement(i);selecting=false;if(!listModel.isEmpty())clips.setSelectedIndex(0);}
    private void closeAudio(){if(audio!=null){audio.stop();audio.close();audio=null;}stopAt=Double.POSITIVE_INFINITY;}
    private void load(ReviewData.Item item){
        editDelay.stop();closeAudio();loading=true;dirty=false;sound=null;words=new ArrayList<>();current=item;selected=-1;cursor=0;reviewStatus="draft";editor.setEnabled(false);original.setText(item.original);editor.setText(item.original);source.setText(item.source+"  ·  начало в исходнике: "+item.sourceStart+" с");tableModel.fireTableDataChanged();wave.repaint();status.setText("Загружаю WAV…");int version=++loadVersion;
        new SwingWorker<ReviewData.Sound,Void>(){
            protected ReviewData.Sound doInBackground()throws Exception{return ReviewData.readSound(item.audio);}
            protected void done(){if(version!=loadVersion)return;try{sound=get();trimStart=0;trimEnd=sound.duration;String warning="";Map<String,Object> saved=null;Path edits=report.resolve("desktop_edits").resolve(item.uid+".json");
                if(Files.exists(edits)){saved=Json.object(Json.parse(Files.readString(edits,StandardCharsets.UTF_8)));if(!item.original.equals(saved.get("original_text")))throw new IllegalArgumentException("Исходная расшифровка изменилась: сохранённые правки не применены");editor.setText((String)saved.get("corrected_text"));reviewStatus=String.valueOf(saved.get("status"));}
                if(saved!=null&&saved.containsKey("trim_start")){trimStart=((Number)saved.get("trim_start")).doubleValue();trimEnd=((Number)saved.get("trim_end")).doubleValue();ReviewData.trimFrames(sound,trimStart,trimEnd);}updateTrim();
                words=ReviewData.estimate(editor.getText(),sound,trimStart,trimEnd);ReviewData.applyFlags(words,item,editor.getText());
                if(saved!=null)ReviewData.restoreTimes(words,Json.array(saved.get("words")),sound.duration,null);
                try{audio=AudioSystem.getClip();audio.open(sound.format,sound.bytes,0,sound.bytes.length);}catch(Exception e){warning=" Звук недоступен: "+e.getMessage();}
                tableModel.fireTableDataChanged();status.setText("Статус: "+reviewStatus+". Двойной щелчок по слову — прослушать окрестность."+warning);highlight();wave.repaint();
            }catch(Exception e){error(e);}finally{loading=false;editor.setEnabled(sound!=null);dirty=false;}}
        }.execute();
    }
    private void changed(){if(!loading){dirty=true;reviewStatus="draft";editDelay.restart();status.setText("Есть несохранённые правки. Метки после изменения текста будут приблизительными.");}}
    private void rebuild(){editDelay.stop();if(sound==null)return;words=ReviewData.estimate(editor.getText(),sound,trimStart,trimEnd);ReviewData.applyFlags(words,current,editor.getText());selected=-1;tableModel.fireTableDataChanged();highlight();wave.repaint();}
    private void updateTrim(){updating=true;trimFrom.setValue(trimStart);trimTo.setValue(trimEnd);updating=false;trimInfo.setText(String.format(Locale.ROOT,"Сохранится %.2f с · удалено слева %.2f с, справа %.2f с. Кнопка ▶ играет оставшийся участок.",trimEnd-trimStart,trimStart,sound.duration-trimEnd));}
    private void setTrim(double start,double end){if(sound==null||loading)return;try{long[] frames=ReviewData.trimFrames(sound,start,end);if(audio!=null)audio.stop();trimStart=frames[0]/sound.format.getFrameRate();trimEnd=frames[1]/sound.format.getFrameRate();updateTrim();rebuild();seek(trimStart);dirty=true;reviewStatus="draft";status.setText("Обрезка изменена; позиции слов пересчитаны. Удалите из текста отрезанную речь и сохраните.");}catch(Exception e){error(e);}}
    private boolean save(String state){if(current==null||sound==null||loading)return false;try{if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return false;trimFrom.commitEdit();trimTo.commitEdit();double a=((Number)trimFrom.getValue()).doubleValue(),b=((Number)trimTo.getValue()).doubleValue();ReviewData.trimFrames(sound,a,b);if(Math.abs(a-trimStart)>.00001||Math.abs(b-trimEnd)>.00001)setTrim(a,b);if(editDelay.isRunning())rebuild();ReviewData.save(report,current,editor.getText(),words,state,sound,trimStart,trimEnd);dirty=false;reviewStatus=state;status.setText("Сохранены текст, границы и WAV в desktop_edits · "+state);return true;}catch(Exception e){error(e);return false;}}
    private boolean confirmLeave(){if(!dirty)return true;int choice=JOptionPane.showConfirmDialog(this,"Сохранить правки перед переходом?","Несохранённый текст / метки",JOptionPane.YES_NO_CANCEL_OPTION);return choice==JOptionPane.NO_OPTION||(choice==JOptionPane.YES_OPTION&&save("draft"));}
    private void next(){int j=clips.getSelectedIndex();if(j+1<listModel.size())clips.setSelectedIndex(j+1);else status.setText("Последний фрагмент в списке.");}
    private void seek(double seconds){if(sound==null)return;cursor=Math.max(0,Math.min(sound.duration,seconds));if(audio!=null)audio.setMicrosecondPosition((long)(cursor*1_000_000));updating=true;slider.setValue((int)(10000*cursor/sound.duration));updating=false;wave.repaint();}
    private void select(int index){if(index<0||index>=words.size())return;table.setRowSelectionInterval(index,index);table.scrollRectToVisible(table.getCellRect(index,0,true));}
    private void manual(double seconds){if(selected<0)return;try{ReviewData.setStart(words,selected,seconds,sound.duration);dirty=true;tableModel.fireTableRowsUpdated(0,words.size()-1);wave.repaint();status.setText("Позиция уточнена вручную; сохраните правки.");}catch(Exception e){error(e);}}
    private void toggle(){if(audio==null)return;if(audio.isRunning())audio.stop();else{if(cursor<trimStart||cursor>=trimEnd-.02)seek(trimStart);stopAt=trimEnd;audio.start();}}
    private void playWord(){if(audio==null||selected<0||selected>=words.size())return;ReviewData.Word w=words.get(selected);audio.stop();seek(Math.max(trimStart,w.start-.5));stopAt=Math.min(trimEnd,Math.max(w.end,w.start+.3)+.5);audio.start();}
    private void tick(){if(audio!=null&&audio.isRunning()){cursor=audio.getMicrosecondPosition()/1_000_000.0;if(cursor>=stopAt)audio.stop();updating=true;slider.setValue((int)(10000*cursor/sound.duration));updating=false;wave.repaint();}position.setText(String.format(Locale.ROOT,"%.2f / %.2f с",cursor,sound==null?0:sound.duration));}
    private void highlight(){editor.getHighlighter().removeAllHighlights();try{for(int j=0;j<words.size();j++){ReviewData.Word w=words.get(j);if(j==selected||!w.flags.isEmpty())editor.getHighlighter().addHighlight(w.from,w.to,new DefaultHighlighter.DefaultHighlightPainter(j==selected?new Color(255,220,100):new Color(255,195,185)));}}catch(Exception ignored){}}
    private void importTimes(){if(current==null||sound==null)return;if(editDelay.isRunning())rebuild();JFileChooser c=new JFileChooser(report.toFile());if(c.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Map<String,Object> m=Json.object(Json.parse(Files.readString(c.getSelectedFile().toPath(),StandardCharsets.UTF_8)));if(!current.uid.equals(m.get("uid"))||!editor.getText().equals(m.get("text")))throw new IllegalArgumentException("uid и text должны точно совпадать с текущим фрагментом");ReviewData.restoreTimes(words,Json.array(m.get("words")),sound.duration,"imported");dirty=true;tableModel.fireTableDataChanged();wave.repaint();status.setText("Внешние метки импортированы; их точность зависит от источника. Сохраните правки.");}catch(Exception e){error(e);}}
    private final class WordTable extends AbstractTableModel{
        String[] cols={"Слово","Начало, с","Конец, с","Метки времени","Замечания морфологии"};
        public int getRowCount(){return words.size();}public int getColumnCount(){return cols.length;}public String getColumnName(int c){return cols[c];}
        public boolean isCellEditable(int r,int c){return c==1;}
        public Object getValueAt(int r,int c){ReviewData.Word w=words.get(r);switch(c){case 0:return w.text;case 1:return String.format(Locale.ROOT,"%.3f",w.start);case 2:return String.format(Locale.ROOT,"%.3f",w.end);case 3:return w.kind.equals("manual")?"Вручную":w.kind.equals("imported")?"Импорт":"≈ Оценка";default:return w.flags;}}
        public void setValueAt(Object value,int r,int c){try{ReviewData.setStart(words,r,Double.parseDouble(value.toString().replace(',','.')),sound.duration);dirty=true;fireTableDataChanged();wave.repaint();}catch(Exception e){error(e);}}
    }
    private final class Wave extends JPanel{
        final List<Rectangle> labels=new ArrayList<>();int dragging=-1;boolean moved;
        Wave(){setBackground(Color.WHITE);setToolTipText("Щелчок по волне — перемотка. Подпись слова можно перетащить.");MouseAdapter mouse=new MouseAdapter(){
            public void mousePressed(MouseEvent e){dragging=-1;moved=false;for(int j=labels.size()-1;j>=0;j--)if(labels.get(j).contains(e.getPoint())){dragging=j;select(j);break;}if(dragging<0)seek(at(e.getX()));}
            public void mouseDragged(MouseEvent e){if(dragging>=0){moved=true;seek(at(e.getX()));}else seek(at(e.getX()));}
            public void mouseReleased(MouseEvent e){if(dragging>=0&&moved){selected=dragging;manual(at(e.getX()));}dragging=-1;}
            public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&selected>=0)playWord();}
        };addMouseListener(mouse);addMouseMotionListener(mouse);}
        double at(int x){return sound==null?0:Math.max(0,Math.min(sound.duration,(x-12.0)/Math.max(1,getWidth()-24)*sound.duration));}
        int x(double t){return 12+(int)(t/sound.duration*(getWidth()-24));}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);labels.clear();if(sound==null)return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(75,120,155));int mid=44;
            for(int j=0;j<sound.peaks.length;j++){int xx=x(j*.025);int h=(int)(sound.peaks[j]*40);g.drawLine(xx,mid-h,xx,mid+h);}
            g.setColor(new Color(100,100,100,100));g.fillRect(0,0,x(trimStart),getHeight());g.fillRect(x(trimEnd),0,getWidth()-x(trimEnd),getHeight());g.setColor(new Color(0,140,75));g.drawLine(x(trimStart),0,x(trimStart),getHeight());g.drawLine(x(trimEnd),0,x(trimEnd),getHeight());
            for(int j=0;j<words.size();j++){ReviewData.Word w=words.get(j);int xx=x(w.start),yy=96+(j%4)*21;String label=(w.kind.equals("approximate")?"≈ ":"")+w.text;int width=g.getFontMetrics().stringWidth(label)+6;Rectangle rect=new Rectangle(Math.max(0,Math.min(getWidth()-width,xx)),yy-14,width,19);labels.add(rect);g.setColor(j==selected?new Color(255,220,100):w.flags.isEmpty()?new Color(225,237,247):new Color(255,195,185));g.fill(rect);g.setColor(Color.DARK_GRAY);g.drawString(label,rect.x+3,yy);g.setColor(new Color(170,180,190));g.drawLine(xx,65,xx,yy-15);}
            g.setColor(new Color(205,35,35));g.drawLine(x(cursor),0,x(cursor),getHeight());g.dispose();}
    }
    public static void main(String[] args)throws Exception{
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());Path initial=args.length>0?Paths.get(args[0]):null;
        SwingUtilities.invokeLater(()->{try{ReviewApp app=new ReviewApp(initial);app.setVisible(true);
            if(args.length>1&&args[1].startsWith("--smoke")){javax.swing.Timer timer=new javax.swing.Timer(1000,null);timer.addActionListener(e->{if(app.loading||app.sound==null)return;try{if(app.words.isEmpty())throw new IllegalStateException("No words loaded");if(args[1].equals("--smoke-trim")){app.setTrim(1,app.sound.duration-.5);if(app.trimStart!=1||!app.dirty)throw new IllegalStateException("Trim UI failed");}java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D canvas=image.createGraphics();app.paint(canvas);canvas.dispose();javax.imageio.ImageIO.write(image,"png",Paths.get(args[2]).toFile());System.out.println("PASS: desktop loaded "+app.items.size()+" clips, "+app.words.size()+" words, audio="+(app.audio!=null));app.closeAudio();app.dispose();System.exit(0);}catch(Exception failure){failure.printStackTrace();System.exit(1);}});timer.start();}
        }catch(Exception e){e.printStackTrace();JOptionPane.showMessageDialog(null,e.toString());System.exit(1);}});
    }
}
