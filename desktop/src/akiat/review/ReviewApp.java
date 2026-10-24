package akiat.review;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.DefaultHighlighter;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
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
    private final JTextArea effective=new JTextArea(3,50);
    private boolean effectiveManual,effectiveUpdating,effectiveStale;
    private final JLabel effectiveInfo=new JLabel(" ");
    private final JLabel source=new JLabel("Выберите отчёт"), status=new JLabel(" "), position=new JLabel("0.00 / 0.00 с");
    private final JSlider slider=new JSlider(0,10000,0);
    private final JSpinner trimFrom=new JSpinner(new SpinnerNumberModel(0.0,0.0,3600.0,.05));
    private final JSpinner trimTo=new JSpinner(new SpinnerNumberModel(0.0,0.0,3600.0,.05));
    private final JLabel trimInfo=new JLabel(" ");
    private double trimStart,trimEnd;
    private double playbackSpeed=1;
    private int trimTool;
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
        Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280,screen.width),Math.min(920,screen.height));setLocationRelativeTo(null);editDelay.setRepeats(false);
        Font font=new Font("Segoe UI",Font.PLAIN,16);editor.setFont(font);original.setFont(font);original.setEditable(false);
        editor.setLineWrap(true);editor.setWrapStyleWord(true);original.setLineWrap(true);original.setWrapStyleWord(true);
        effective.setFont(font);effective.setLineWrap(true);effective.setWrapStyleWord(true);
        table.setRowHeight(27);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);clips.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clips.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
            JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);ReviewData.Item item=(ReviewData.Item)value;
            if(item.status.equals("excluded")){label.setBackground(selected?new Color(220,130,130):new Color(255,220,220));label.setForeground(new Color(125,15,15));}
            else if(item.status.equals("reviewed")){label.setBackground(selected?new Color(140,200,155):new Color(220,245,225));label.setForeground(new Color(20,85,35));}
            label.setToolTipText(item.toString());return label;}});
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.add(button("Открыть отчёт…",()->chooseReport()));
        JTextField search=new JTextField(26);top.add(search);top.add(button("Найти",()->filter(search.getText())));top.add(button("Все",()->{search.setText("");filter("");}));
        top.add(button("Папка исправлений",()->{try{Files.createDirectories(report.resolve("desktop_edits"));Desktop.getDesktop().open(report.resolve("desktop_edits").toFile());}catch(Exception e){error(e);}}));
        JPanel right=new JPanel(new BorderLayout(5,5));JPanel center=new JPanel();center.setLayout(new BoxLayout(center,BoxLayout.Y_AXIS));
        center.add(source);center.add(new JLabel("Исходная расшифровка (пометки относятся к ней):"));center.add(new JScrollPane(original));
        center.add(new JLabel("Исправленный текст — редактируйте здесь. Изменение текста пересчитывает приблизительные метки:"));center.add(new JScrollPane(editor));
        center.add(new JLabel("Эффективный текст — финальная расшифровка для обучения:"));center.add(new JScrollPane(effective));
        JPanel effectiveActions=new JPanel(new FlowLayout(FlowLayout.LEFT));effectiveActions.add(button("Заново из слов внутри границ",()->{
            if(sound==null||loading)return;
            if(effectiveManual&&JOptionPane.showConfirmDialog(this,"Заменить ручную финальную редактуру текстом из меток?","Пересборка текста",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            if(editDelay.isRunning())rebuild();effectiveManual=false;refreshEffective();dirty=true;reviewStatus="draft";
        }));effectiveActions.add(effectiveInfo);center.add(effectiveActions);
        center.add(new JLabel("≈ Приблизительные позиции по длине слов и активности звука. Это НЕ распознанные границы слов."));
        wave.setPreferredSize(new Dimension(850,185));wave.setMinimumSize(new Dimension(100,185));center.add(wave);center.add(slider);
        JPanel playback=new JPanel(new FlowLayout(FlowLayout.LEFT));playback.add(button("▶ / Пауза",()->toggle()));playback.add(button("−0,5 с",()->seek(cursor-.5)));playback.add(button("+0,5 с",()->seek(cursor+.5)));
        playback.add(button("Слово ±0,5 с",()->playWord()));playback.add(position);
        JComboBox<String> speeds=new JComboBox<>(new String[]{"0,5×","0,75×","1×"});speeds.setSelectedIndex(2);speeds.setToolTipText("Замедленное прослушивание понижает тон. Файл и временные метки не меняются.");speeds.addActionListener(e->changeSpeed(new double[]{.5,.75,1}[speeds.getSelectedIndex()]));playback.add(new JLabel("Скорость:"));playback.add(speeds);center.add(playback);
        JPanel trim=new JPanel(new GridLayout(0,1,0,3));trim.setBorder(BorderFactory.createTitledBorder("Обрезка краёв аудио"));
        JPanel limits=new JPanel(new FlowLayout(FlowLayout.LEFT));limits.add(new JLabel("Оставить от, с:"));limits.add(trimFrom);limits.add(new JLabel("до, с:"));limits.add(trimTo);
        limits.add(button("Применить границы",()->{try{trimFrom.commitEdit();trimTo.commitEdit();setTrim(((Number)trimFrom.getValue()).doubleValue(),((Number)trimTo.getValue()).doubleValue());}catch(Exception e){error(e);}}));trim.add(limits);
        JPanel cutButtons=new JPanel(new GridLayout(1,3,5,0));
        cutButtons.add(button("Начало = курсор",()->setTrim(cursor,trimEnd)));cutButtons.add(button("Конец = курсор",()->setTrim(trimStart,cursor)));
        cutButtons.add(button("Сброс обрезки",()->{if(sound!=null)setTrim(0,sound.duration);}));trim.add(cutButtons);trim.add(trimInfo);
        JPanel tools=new JPanel(new GridLayout(1,3,5,0));ButtonGroup toolGroup=new ButtonGroup();
        String[] toolNames={"Курсор: прослушивание","Ножницы: убрать слева","Ножницы: убрать справа"};
        for(int n=0;n<toolNames.length;n++){final int tool=n;JToggleButton b=new JToggleButton(toolNames[n],n==0);toolGroup.add(b);tools.add(b);b.addActionListener(e->{trimTool=tool;wave.setCursor(Cursor.getPredefinedCursor(tool==0?Cursor.DEFAULT_CURSOR:Cursor.CROSSHAIR_CURSOR));status.setText(tool==0?"Перетащите маркеры НАЧАЛО / КОНЕЦ на дорожке.":"Щёлкните по дорожке: красная часть будет удалена. Затем сохраните правки.");});}trim.add(tools);
        JPanel actions=new JPanel(new BorderLayout(5,5));
        JButton exclude=button("Исключить фрагмент целиком → следующий",()->{if(save("excluded"))next();});
        exclude.setFont(exclude.getFont().deriveFont(Font.BOLD,16f));exclude.setForeground(new Color(155,25,25));
        actions.add(exclude,BorderLayout.NORTH);actions.add(trim,BorderLayout.CENTER);right.add(actions,BorderLayout.NORTH);
        JPanel timing=new JPanel(new FlowLayout(FlowLayout.LEFT));timing.add(button("Начало слова = курсор",()->manual(cursor)));timing.add(button("Импорт меток…",()->importTimes()));
        timing.add(new JLabel("Перетаскивайте подписи слов или меняйте начало в таблице."));center.add(timing);
        JScrollPane wordScroll=new JScrollPane(table);wordScroll.setPreferredSize(new Dimension(850,220));center.add(wordScroll);
        JScrollPane contentScroll=new JScrollPane(center);contentScroll.getVerticalScrollBar().setUnitIncrement(24);right.add(contentScroll,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new GridLayout(1,3,5,0));bottom.add(button("Сохранить черновик",()->save("draft")));
        bottom.add(button("Проверено → следующий",()->{if(save("reviewed"))next();}));bottom.add(button("Следующий",()->next()));
        JPanel footer=new JPanel(new BorderLayout());footer.add(bottom,BorderLayout.NORTH);footer.add(status,BorderLayout.SOUTH);right.add(footer,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(clips),right);split.setDividerLocation(300);add(top,BorderLayout.NORTH);add(split,BorderLayout.CENTER);
        clips.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!selecting){ReviewData.Item item=clips.getSelectedValue();if(item!=null&&item!=current){if(confirmLeave())load(item);else{selecting=true;clips.setSelectedValue(current,true);selecting=false;}}}});
        clips.addMouseListener(new MouseAdapter(){
            private void popup(MouseEvent e){if(!e.isPopupTrigger())return;int index=clips.locationToIndex(e.getPoint());if(index<0||!clips.getCellBounds(index,index).contains(e.getPoint()))return;
                ReviewData.Item item=listModel.get(index);JPopupMenu menu=new JPopupMenu();
                JMenuItem copy=new JMenuItem("Копировать ID: "+item.uid);copy.addActionListener(x->{try{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(item.uid),null);status.setText("ID скопирован: "+item.uid);}catch(Exception failure){error(failure);}});menu.add(copy);
                JMenuItem recognize=new JMenuItem("Перераспознать оставленный участок…");recognize.addActionListener(x->{
                    if(item!=current){clips.setSelectedValue(item,true);if(current!=item)return;
                        javax.swing.Timer waitForClip=new javax.swing.Timer(100,null);waitForClip.addActionListener(t->{if(current!=item||!loading){waitForClip.stop();if(current==item&&sound!=null)retranscribe();}});waitForClip.start();return;
                    }retranscribe();});menu.add(recognize);menu.show(clips,e.getX(),e.getY());
            }
            public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}
        });
        table.getSelectionModel().addListSelectionListener(e->{if(!updating&&!e.getValueIsAdjusting()){selected=table.getSelectedRow();if(selected>=0&&selected<words.size())seek(words.get(selected).start);highlight();wave.repaint();}});
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)playWord();}});
        editor.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int caret=editor.getCaretPosition();for(int j=0;j<words.size();j++)if(caret>=words.get(j).from&&caret<=words.get(j).to){select(j);if(e.getClickCount()==2)playWord();break;}}});
        editor.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}});
        effective.getDocument().addDocumentListener(new DocumentListener(){private void change(){if(!loading&&!effectiveUpdating){effectiveManual=true;dirty=true;reviewStatus="draft";effectiveInfo.setText("Ручная финальная редактура");}}public void insertUpdate(DocumentEvent e){change();}public void removeUpdate(DocumentEvent e){change();}public void changedUpdate(DocumentEvent e){change();}});
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
    private void retranscribe(){
        if(current==null||sound==null||loading){status.setText("Дождитесь загрузки WAV.");return;}
        try{
            trimFrom.commitEdit();trimTo.commitEdit();double a=((Number)trimFrom.getValue()).doubleValue(),b=((Number)trimTo.getValue()).doubleValue();
            ReviewData.trimFrames(sound,a,b);if(Math.abs(a-trimStart)>.00001||Math.abs(b-trimEnd)>.00001)setTrim(a,b);
            if(editDelay.isRunning())rebuild();if(audio!=null)audio.stop();
            Path root=Paths.get("").toAbsolutePath();while(root!=null&&!Files.isRegularFile(root.resolve("training/retranscribe_clip.py")))root=root.getParent();
            if(root==null)throw new IllegalStateException("Запустите редактор через desktop/start.ps1: не найден распознаватель");
            Path folder=report.resolve("desktop_edits/asr");Files.createDirectories(folder);String job=current.uid+"_"+UUID.randomUUID();Path output=folder.resolve(job+".json"),log=folder.resolve(job+".log");
            ProcessBuilder builder=new ProcessBuilder(root.resolve(".venv/Scripts/python.exe").toString(),"-u",root.resolve("training/retranscribe_clip.py").toString(),"--audio",current.audio.toString(),"--start",Double.toString(trimStart),"--end",Double.toString(trimEnd),"--output",output.toString());
            builder.directory(root.toFile());builder.environment().put("PYTHONUTF8","1");builder.redirectErrorStream(true);builder.redirectOutput(log.toFile());
            Process process=builder.start();boolean[] cancelled={false};
            JDialog progress=new JDialog(this,"Перераспознавание · "+current.uid,true);progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            JLabel message=new JLabel("Распознавание на CPU. Ожидайте результат…");JProgressBar bar=new JProgressBar();bar.setIndeterminate(true);
            Runnable cancel=()->{cancelled[0]=true;process.destroyForcibly();message.setText("Отмена…");};
            progress.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){cancel.run();}});
            JPanel panel=new JPanel(new BorderLayout(10,10));panel.setBorder(BorderFactory.createEmptyBorder(15,15,15,15));panel.add(message,BorderLayout.NORTH);panel.add(bar,BorderLayout.CENTER);panel.add(button("Отменить",cancel),BorderLayout.SOUTH);progress.add(panel);progress.pack();progress.setLocationRelativeTo(this);
            new SwingWorker<Integer,Void>(){
                protected Integer doInBackground()throws Exception{return process.waitFor();}
                protected void done(){progress.dispose();if(cancelled[0]){status.setText("Перераспознавание отменено.");return;}try{
                    if(get()!=0)throw new IllegalStateException("Ошибка распознавания. Журнал: "+log);
                    Map<String,Object> result=Json.object(Json.parse(Files.readString(output,StandardCharsets.UTF_8)));String text=(String)result.get("text");
                    if(text==null||text.trim().isEmpty()){status.setText("Распознаватель не вернул текст. Прежняя редактура сохранена.");return;}
                    JTextArea preview=new JTextArea(text,8,60);preview.setLineWrap(true);preview.setWrapStyleWord(true);preview.setFont(editor.getFont());preview.setEditable(false);
                    int choice=JOptionPane.showOptionDialog(ReviewApp.this,new Object[]{"Обновить исходную расшифровку, исправленный и эффективный текст? Ручная редактура будет заменена.",new JScrollPane(preview)},"Результат распознавания и морфологической проверки",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"Применить новую расшифровку","Оставить прежнюю"},"Оставить прежнюю");
                    if(choice==0)applyRecognition(result);
                }catch(Exception failure){error(failure);}}
            }.execute();progress.setVisible(true);
        }catch(Exception failure){error(failure);}
    }
    private void error(Exception e){status.setText("Ошибка: "+e.getMessage());JOptionPane.showMessageDialog(this,e.getMessage(),"Akiat",JOptionPane.ERROR_MESSAGE);}
    private void applyRecognition(Map<String,Object> result){
        String text=(String)result.get("text");double start=((Number)result.get("start")).doubleValue(),end=((Number)result.get("end")).doubleValue();
        List<ReviewData.Word> recognized=ReviewData.estimate(text,sound,start,end);
        editDelay.stop();loading=true;try{
            current.recognition=result;original.setText(text);editor.setText(text);words=recognized;
            ReviewData.applyFlags(words,current,text);effectiveManual=false;effectiveStale=false;selected=-1;
            refreshEffective();tableModel.fireTableDataChanged();highlight();wave.repaint();
        }finally{loading=false;}
        dirty=true;reviewStatus="draft";status.setText("Новая расшифровка применена. Красным — замечания морфологии/корпуса, не доказательство бессмысленности. Проверьте и сохраните.");
    }
    private void chooseReport(){if(!confirmLeave())return;JFileChooser c=new JFileChooser(report==null?".":report.toString());c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)try{openReport(c.getSelectedFile().toPath());}catch(Exception e){error(e);}}
    private void openReport(Path path)throws Exception{List<ReviewData.Item> loaded=ReviewData.readReport(path);closeAudio();report=path.toAbsolutePath();items=loaded;current=null;dirty=false;filter("");setTitle("Akiat v10 · Обрезка и исключение — "+report.getFileName()+" · "+items.size()+" подозрительных фрагментов");}
    private void filter(String text){if(!confirmLeave())return;selecting=true;listModel.clear();String q=text.toLowerCase(Locale.ROOT);for(ReviewData.Item i:items)if((i.uid+" "+i.source+" "+i.original).toLowerCase(Locale.ROOT).contains(q))listModel.addElement(i);selecting=false;if(!listModel.isEmpty())clips.setSelectedIndex(0);}
    private void closeAudio(){if(audio!=null){audio.stop();audio.close();audio=null;}stopAt=Double.POSITIVE_INFINITY;}
    private void openAudio()throws Exception{byte[] data=ReviewData.playbackBytes(sound,playbackSpeed);Clip next=AudioSystem.getClip();try{next.open(sound.format,data,0,data.length);audio=next;}catch(Exception e){next.close();throw e;}}
    private void changeSpeed(double speed){boolean playing=audio!=null&&audio.isRunning();if(playing)cursor=audio.getMicrosecondPosition()/1_000_000.0*playbackSpeed;double end=stopAt;closeAudio();playbackSpeed=speed;if(sound==null||loading)return;try{openAudio();seek(cursor);stopAt=end;if(playing&&cursor<Math.min(trimEnd,stopAt))audio.start();}catch(Exception e){error(e);}}
    private void load(ReviewData.Item item){
        editDelay.stop();closeAudio();loading=true;effective.setEnabled(false);effective.setText("");dirty=false;sound=null;words=new ArrayList<>();current=item;item.recognition=null;selected=-1;cursor=0;reviewStatus="draft";editor.setEnabled(false);original.setText(item.original);editor.setText(item.original);source.setText(item.source+"  ·  начало в исходнике: "+item.sourceStart+" с");tableModel.fireTableDataChanged();wave.repaint();status.setText("Загружаю WAV…");int version=++loadVersion;
        new SwingWorker<ReviewData.Sound,Void>(){
            protected ReviewData.Sound doInBackground()throws Exception{return ReviewData.readSound(item.audio);}
            protected void done(){if(version!=loadVersion)return;try{sound=get();trimStart=0;trimEnd=sound.duration;String warning="";Map<String,Object> saved=null;Path edits=report.resolve("desktop_edits").resolve(item.uid+".json");
                if(Files.exists(edits)){saved=Json.object(Json.parse(Files.readString(edits,StandardCharsets.UTF_8)));if(!item.original.equals(saved.get("original_text")))throw new IllegalArgumentException("Исходная расшифровка изменилась: сохранённые правки не применены");editor.setText((String)saved.get("corrected_text"));reviewStatus=String.valueOf(saved.get("status"));}
                if(saved!=null&&saved.containsKey("trim_start")){trimStart=((Number)saved.get("trim_start")).doubleValue();trimEnd=((Number)saved.get("trim_end")).doubleValue();ReviewData.trimFrames(sound,trimStart,trimEnd);}updateTrim();
                if(saved!=null&&saved.get("recognition") instanceof Map){item.recognition=Json.object(saved.get("recognition"));original.setText((String)item.recognition.get("text"));}
                words=ReviewData.estimate(editor.getText(),sound,trimStart,trimEnd);ReviewData.applyFlags(words,item,editor.getText());
                if(saved!=null&&ReviewData.restoreEditableTimes(words,Json.array(saved.get("words")),sound.duration)){warning=" Исправлен порядок сохранённых меток. Проверьте и сохраните фрагмент.";}
                effectiveManual=saved!=null&&Boolean.TRUE.equals(saved.get("effective_text_manual"));effectiveStale=saved!=null&&Boolean.TRUE.equals(saved.get("effective_text_needs_review"));
                effective.setText(saved!=null&&effectiveManual&&saved.containsKey("effective_text")?(String)saved.get("effective_text"):ReviewData.effectiveText(editor.getText(),words,trimStart,trimEnd));
                effectiveInfo.setText(effectiveStale?"Границы менялись — проверьте финальный текст":effectiveManual?"Ручная финальная редактура":"Автоматически: только целые слова внутри границ");effective.setEnabled(true);
                try{openAudio();}catch(Exception e){warning=" Звук недоступен: "+e.getMessage();}
                tableModel.fireTableDataChanged();status.setText("Статус: "+reviewStatus+". Двойной щелчок по слову — прослушать окрестность."+warning);highlight();wave.repaint();
            }catch(Exception e){error(e);}finally{loading=false;editor.setEnabled(sound!=null);dirty=false;}}
        }.execute();
    }
    private void changed(){if(!loading){dirty=true;reviewStatus="draft";editDelay.restart();status.setText("Есть несохранённые правки. Метки после изменения текста будут приблизительными.");}}
    private void refreshEffective(){
        if(effectiveManual){effectiveStale=true;effectiveInfo.setText("Метки/текст менялись — проверьте финальную редактуру");return;}
        effectiveUpdating=true;try{effective.setText(ReviewData.effectiveText(editor.getText(),words,trimStart,trimEnd));}finally{effectiveUpdating=false;}
        effectiveStale=false;effectiveInfo.setText("Автоматически: только целые слова внутри границ");
    }
    private void rebuild(){editDelay.stop();if(sound==null)return;words=ReviewData.reconcile(editor.getText(),sound,words);ReviewData.applyFlags(words,current,editor.getText());selected=-1;refreshEffective();tableModel.fireTableDataChanged();highlight();wave.repaint();}
    private void updateTrim(){updating=true;trimFrom.setValue(trimStart);trimTo.setValue(trimEnd);updating=false;trimInfo.setText(String.format(Locale.ROOT,"Сохранится %.2f с · удалено слева %.2f с, справа %.2f с. Кнопка ▶ играет оставшийся участок.",trimEnd-trimStart,trimStart,sound.duration-trimEnd));}
    private void setTrim(double start,double end){if(sound==null||loading)return;try{long[] frames=ReviewData.trimFrames(sound,start,end);if(audio!=null)audio.stop();trimStart=frames[0]/sound.format.getFrameRate();trimEnd=frames[1]/sound.format.getFrameRate();updateTrim();refreshEffective();tableModel.fireTableDataChanged();wave.repaint();seek(trimStart);dirty=true;reviewStatus="draft";status.setText("Обрезка изменена; метки слов остались на месте. Удалите из текста отрезанную речь и сохраните.");}catch(Exception e){error(e);}}
    private boolean save(String state){if(current==null||sound==null||loading)return false;try{if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return false;trimFrom.commitEdit();trimTo.commitEdit();double a=((Number)trimFrom.getValue()).doubleValue(),b=((Number)trimTo.getValue()).doubleValue();ReviewData.trimFrames(sound,a,b);if(Math.abs(a-trimStart)>.00001||Math.abs(b-trimEnd)>.00001)setTrim(a,b);if(editDelay.isRunning())rebuild();ReviewData.save(report,current,editor.getText(),words,state,sound,trimStart,trimEnd,effective.getText(),effectiveManual,state.equals("reviewed")?false:effectiveStale);if(state.equals("reviewed"))effectiveStale=false;dirty=false;reviewStatus=state;current.status=state;clips.repaint();status.setText("Сохранены текст, границы и WAV в desktop_edits · "+state);return true;}catch(Exception e){error(e);return false;}}
    private boolean confirmLeave(){if(!dirty)return true;int choice=JOptionPane.showConfirmDialog(this,"Сохранить правки перед переходом?","Несохранённый текст / метки",JOptionPane.YES_NO_CANCEL_OPTION);return choice==JOptionPane.NO_OPTION||(choice==JOptionPane.YES_OPTION&&save("draft"));}
    private void next(){int j=clips.getSelectedIndex();if(j+1<listModel.size())clips.setSelectedIndex(j+1);else status.setText("Последний фрагмент в списке.");}
    private void seek(double seconds){if(sound==null)return;cursor=Math.max(0,Math.min(sound.duration,seconds));if(audio!=null)audio.setMicrosecondPosition((long)(cursor/playbackSpeed*1_000_000));updating=true;slider.setValue((int)(10000*cursor/sound.duration));updating=false;wave.repaint();}
    private void select(int index){if(index<0||index>=words.size())return;table.setRowSelectionInterval(index,index);table.scrollRectToVisible(table.getCellRect(index,0,true));}
    private void manual(double seconds){if(selected<0)return;try{ReviewData.setStart(words,selected,seconds,sound.duration);refreshEffective();dirty=true;tableModel.fireTableRowsUpdated(0,words.size()-1);wave.repaint();status.setText("Позиция уточнена вручную; сохраните правки.");}catch(Exception e){error(e);}}
    private void toggle(){if(audio==null)return;if(audio.isRunning())audio.stop();else{if(cursor<trimStart||cursor>=trimEnd-.02)seek(trimStart);stopAt=trimEnd;audio.start();}}
    private void playWord(){if(audio==null||selected<0||selected>=words.size())return;ReviewData.Word w=words.get(selected);if(ReviewData.trimState(w,trimStart,trimEnd).equals("outside")){status.setText("Слово полностью за границами обрезки.");return;}audio.stop();seek(Math.max(trimStart,w.start-.5));stopAt=Math.min(trimEnd,Math.max(w.end,w.start+.3)+.5);audio.start();}
    private void tick(){if(audio!=null&&audio.isRunning()){cursor=audio.getMicrosecondPosition()/1_000_000.0*playbackSpeed;if(cursor>=stopAt)audio.stop();updating=true;slider.setValue((int)(10000*cursor/sound.duration));updating=false;wave.repaint();}position.setText(String.format(Locale.ROOT,"%.2f / %.2f с",cursor,sound==null?0:sound.duration));}
    private void highlight(){editor.getHighlighter().removeAllHighlights();try{for(int j=0;j<words.size();j++){ReviewData.Word w=words.get(j);if(j==selected||!w.flags.isEmpty()||w.edited)editor.getHighlighter().addHighlight(w.from,w.to,new DefaultHighlighter.DefaultHighlightPainter(j==selected?new Color(255,220,100):w.edited?new Color(190,220,255):new Color(255,195,185)));}}catch(Exception ignored){}}
    private void importTimes(){if(current==null||sound==null)return;if(editDelay.isRunning())rebuild();JFileChooser c=new JFileChooser(report.toFile());if(c.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Map<String,Object> m=Json.object(Json.parse(Files.readString(c.getSelectedFile().toPath(),StandardCharsets.UTF_8)));if(!current.uid.equals(m.get("uid"))||!editor.getText().equals(m.get("text")))throw new IllegalArgumentException("uid и text должны точно совпадать с текущим фрагментом");ReviewData.restoreTimes(words,Json.array(m.get("words")),sound.duration,"imported");refreshEffective();dirty=true;tableModel.fireTableDataChanged();wave.repaint();status.setText("Внешние метки импортированы; их точность зависит от источника. Сохраните правки.");}catch(Exception e){error(e);}}
    private final class WordTable extends AbstractTableModel{
        String[] cols={"Слово","Начало, с","Конец, с","Метки времени","Замечания морфологии"};
        public int getRowCount(){return words.size();}public int getColumnCount(){return cols.length;}public String getColumnName(int c){return cols[c];}
        public boolean isCellEditable(int r,int c){return c==1;}
        public Object getValueAt(int r,int c){ReviewData.Word w=words.get(r);switch(c){case 0:return w.text;case 1:return String.format(Locale.ROOT,"%.3f",w.start);case 2:return String.format(Locale.ROOT,"%.3f",w.end);case 3:return w.kind.equals("manual")?"Вручную":w.kind.equals("imported")?"Импорт":"≈ Оценка";default:return w.flags;}}
        public void setValueAt(Object value,int r,int c){try{ReviewData.setStart(words,r,Double.parseDouble(value.toString().replace(',','.')),sound.duration);refreshEffective();dirty=true;reviewStatus="draft";fireTableDataChanged();wave.repaint();}catch(Exception e){error(e);}}
    }
    private final class Wave extends JPanel{
        final List<Rectangle> labels=new ArrayList<>();int dragging=-1,edge;boolean moved;
        Wave(){setBackground(Color.WHITE);setToolTipText("Щелчок по волне — перемотка. Подпись слова можно перетащить.");MouseAdapter mouse=new MouseAdapter(){
            public void mousePressed(MouseEvent e){dragging=-1;edge=0;moved=false;if(sound==null||loading)return;
                if(trimTool!=0){edge=trimTool;cut(e.getX());return;}
                if(e.getY()<28){if(Math.abs(e.getX()-x(trimStart))<=14)edge=1;else if(Math.abs(e.getX()-x(trimEnd))<=14)edge=2;if(edge!=0)return;}
                for(int j=labels.size()-1;j>=0;j--)if(labels.get(j).contains(e.getPoint())){dragging=j;select(j);break;}if(dragging<0)seek(at(e.getX()));}
            public void mouseDragged(MouseEvent e){if(edge!=0){cut(e.getX());return;}if(dragging>=0){moved=true;seek(at(e.getX()));}else seek(at(e.getX()));}
            public void mouseReleased(MouseEvent e){if(edge!=0){cut(e.getX());edge=0;return;}if(dragging>=0&&moved){selected=dragging;manual(at(e.getX()));}dragging=-1;}
            public void mouseClicked(MouseEvent e){if(trimTool==0&&e.getClickCount()==2&&selected>=0)playWord();}
            public void mouseMoved(MouseEvent e){boolean handle=sound!=null&&e.getY()<28&&(Math.abs(e.getX()-x(trimStart))<=14||Math.abs(e.getX()-x(trimEnd))<=14);setCursor(Cursor.getPredefinedCursor(trimTool!=0?Cursor.CROSSHAIR_CURSOR:handle?Cursor.E_RESIZE_CURSOR:Cursor.DEFAULT_CURSOR));}
        };addMouseListener(mouse);addMouseMotionListener(mouse);}
        void cut(int pixel){if(sound==null||loading)return;double value=at(pixel);if(edge==1)setTrim(Math.max(0,Math.min(value,trimEnd-.051)),trimEnd);else if(edge==2)setTrim(trimStart,Math.min(sound.duration,Math.max(value,trimStart+.051)));}
        double at(int x){return sound==null?0:Math.max(0,Math.min(sound.duration,(x-12.0)/Math.max(1,getWidth()-24)*sound.duration));}
        int x(double t){return 12+(int)(t/sound.duration*(getWidth()-24));}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);labels.clear();if(sound==null)return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(75,120,155));int mid=44;
            for(int j=0;j<sound.peaks.length;j++){int xx=x(j*.025);int h=(int)(sound.peaks[j]*40);g.drawLine(xx,mid-h,xx,mid+h);}
            g.setColor(new Color(235,65,65,100));g.fillRect(0,0,x(trimStart),getHeight());g.fillRect(x(trimEnd),0,getWidth()-x(trimEnd),getHeight());
            g.setColor(new Color(165,25,25));if(x(trimStart)>105)g.drawString("УДАЛИТЬ",20,62);if(getWidth()-x(trimEnd)>105)g.drawString("УДАЛИТЬ",x(trimEnd)+8,62);
            g.setColor(new Color(0,130,70));g.setStroke(new BasicStroke(2));g.drawLine(x(trimStart),0,x(trimStart),getHeight());g.drawLine(x(trimEnd),0,x(trimEnd),getHeight());
            for(int j=0;j<words.size();j++){ReviewData.Word w=words.get(j);int xx=x(w.start),yy=96+(j%4)*21;String label=(w.kind.equals("approximate")?"≈ ":"")+w.text;int width=g.getFontMetrics().stringWidth(label)+6;Rectangle rect=new Rectangle(Math.max(0,Math.min(getWidth()-width,xx)),yy-14,width,19);labels.add(rect);String cutState=ReviewData.trimState(w,trimStart,trimEnd);g.setColor(!cutState.equals("inside")?new Color(245,170,170):j==selected?new Color(255,220,100):w.edited?new Color(190,220,255):w.flags.isEmpty()?new Color(225,237,247):new Color(255,195,185));g.fill(rect);g.setColor(Color.DARK_GRAY);g.drawString(label,rect.x+3,yy);if(cutState.equals("outside"))g.drawLine(rect.x+2,yy-5,rect.x+width-2,yy-5);g.setColor(new Color(170,180,190));g.drawLine(xx,65,xx,yy-15);}
            g.setColor(new Color(40,70,210));g.drawLine(x(cursor),28,x(cursor),getHeight());
            for(int side=1;side<=2;side++){int xx=x(side==1?trimStart:trimEnd);g.setColor(new Color(0,130,70));g.fillRect(xx-9,0,18,24);g.setColor(Color.WHITE);g.drawString(side==1?"[":"]",xx-3,17);g.setColor(new Color(0,95,50));String text=side==1?"НАЧАЛО":"КОНЕЦ";g.drawString(text,Math.max(2,Math.min(getWidth()-60,side==1?xx+13:xx-60)),17);}g.dispose();}
    }
    private void smokeTrim(){
        effectiveManual=false;refreshEffective();
        String before=Json.write(ReviewData.wordMaps(words));
        setTrim(0,sound.duration);
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_PRESSED,0,0,wave.x(0),10,1,false));
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_DRAGGED,0,0,wave.x(1),10,1,false));
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_RELEASED,0,0,wave.x(1),10,1,false));
        if(Math.abs(trimStart-1)>.05||!dirty)throw new IllegalStateException("Start handle failed");
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_PRESSED,0,0,wave.x(sound.duration),10,1,false));
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_DRAGGED,0,0,wave.x(sound.duration-1),10,1,false));
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_RELEASED,0,0,wave.x(sound.duration-1),10,1,false));
        if(Math.abs(trimEnd-(sound.duration-1))>.05)throw new IllegalStateException("End handle failed");
        trimTool=2;wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_PRESSED,0,0,wave.x(sound.duration-.5),50,1,false));
        wave.dispatchEvent(new MouseEvent(wave,MouseEvent.MOUSE_RELEASED,0,0,wave.x(sound.duration-.5),50,1,false));trimTool=0;
        if(Math.abs(trimEnd-(sound.duration-.5))>.05)throw new IllegalStateException("Scissors tool failed");
        if(!before.equals(Json.write(ReviewData.wordMaps(words))))throw new IllegalStateException("Trimming moved word timestamps");
        if(!effective.getText().equals(ReviewData.effectiveText(editor.getText(),words,trimStart,trimEnd)))throw new IllegalStateException("Effective text did not follow trim");
        effective.setText("Final manual edit");refreshEffective();
        if(!effectiveManual||!effectiveStale||!effective.getText().equals("Final manual edit"))throw new IllegalStateException("Manual final edit overwritten");
        effectiveManual=false;refreshEffective();
        System.out.println("PASS: effective text follows cuts and preserves manual final edits");
        for(double speed:new double[]{.5,.75,1}){
            changeSpeed(speed);seek(2);
            if(audio==null||Math.abs(audio.getMicrosecondPosition()/1_000_000.0*speed-2)>.002)throw new IllegalStateException("Playback seek mapping failed");
            if(Math.abs(audio.getMicrosecondLength()/1_000_000.0*speed-sound.duration)>.002)throw new IllegalStateException("Playback duration failed");
        }
        System.out.println("PASS: playback speeds and original-time cursor mapping");
        System.out.println("PASS: draggable start/end handles and scissors click");
    }
    public static void main(String[] args)throws Exception{
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());Path initial=args.length>0?Paths.get(args[0]):null;
        SwingUtilities.invokeLater(()->{try{ReviewApp app=new ReviewApp(initial);app.setVisible(true);
            if(args.length>3&&args[1].startsWith("--smoke")){for(ReviewData.Item item:app.items)if(item.uid.equals(args[3])){app.clips.setSelectedValue(item,true);break;}}
            if(args.length>1&&args[1].startsWith("--smoke")){javax.swing.Timer timer=new javax.swing.Timer(1000,null);timer.addActionListener(e->{if(app.loading||app.sound==null)return;try{if(app.words.isEmpty())throw new IllegalStateException("No words loaded");if(args[1].equals("--smoke-trim")){app.smokeTrim();}if(args[1].equals("--smoke-recognition")){Map<String,Object> result=Json.object(Json.parse(Files.readString(Paths.get(args[4]),StandardCharsets.UTF_8)));app.applyRecognition(result);if(!app.original.getText().equals(result.get("text"))||!app.editor.getText().equals(result.get("text"))||app.words.stream().noneMatch(w->!w.flags.isEmpty()))throw new IllegalStateException("Recognition application failed");System.out.println("PASS: recognition replaces baseline/editor and applies fresh flags");}java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D canvas=image.createGraphics();app.paint(canvas);canvas.dispose();javax.imageio.ImageIO.write(image,"png",Paths.get(args[2]).toFile());System.out.println("PASS: desktop loaded "+app.items.size()+" clips, "+app.words.size()+" words, audio="+(app.audio!=null));app.closeAudio();app.dispose();System.exit(0);}catch(Exception failure){failure.printStackTrace();System.exit(1);}});timer.start();}
        }catch(Exception e){e.printStackTrace();JOptionPane.showMessageDialog(null,e.toString());System.exit(1);}});
    }
}
