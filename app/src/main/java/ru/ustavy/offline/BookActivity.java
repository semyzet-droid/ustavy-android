package ru.ustavy.offline;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class BookActivity extends Activity {
 private BookStore store;private JSONObject book;private String id,kind;private int page,total=1,font=20,theme=0;
 private LinearLayout root,bar;private TextView title,status;private ScrollView scroll;private TextView prose;private ZoomPageView picture;
 private android.content.SharedPreferences prefs;private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
 private final AtomicInteger generation=new AtomicInteger();private boolean textMode=false,restoring=false;private String currentText="";
 private Dialog activeDialog;private boolean ocrBusy=false;private PdfRenderer renderer;private ParcelFileDescriptor descriptor;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);prefs=getSharedPreferences("reader",0);id=getIntent().getStringExtra("bookId");store=new BookStore(this);
  try{book=store.get(id);kind=book.getString("kind");total=book.optInt("pages",1);page=Math.max(0,Math.min(total-1,prefs.getInt(id+":page",0)));font=prefs.getInt("font",20);theme=prefs.getInt("theme",0);}
  catch(Exception e){new AlertDialog.Builder(this).setMessage("Книга недоступна: "+e.getMessage()).setPositiveButton("Закрыть",(d,w)->finish()).setOnCancelListener(d->finish()).show();return;}
  root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(12),dp(8),dp(12),0);
  root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(12)+in.getSystemWindowInsetLeft(),dp(8)+in.getSystemWindowInsetTop(),dp(12)+in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
  root.addView(button("← К библиотеке",this::finish));title=new TextView(this);title.setText(book.optString("name"));title.setTextSize(18);title.setPadding(0,dp(5),0,dp(5));root.addView(title);
  status=new TextView(this);status.setTextSize(13);status.setPadding(0,dp(5),0,dp(7));root.addView(status);
  if(!kind.equals("txt"))root.addView(button("Распознать текст",this::recognize));
  picture=new ZoomPageView(this);root.addView(picture,new LinearLayout.LayoutParams(-1,0,1));
  scroll=new ScrollView(this);prose=new TextView(this);prose.setTextIsSelectable(true);prose.setPadding(dp(12),dp(15),dp(12),dp(35));prose.setLineSpacing(dp(5),1.2f);scroll.addView(prose);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroll.setVisibility(View.GONE);
  scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{if(restoring||!kind.equals("txt"))return;handler.removeCallbacks(saveScroll);handler.postDelayed(saveScroll,350);});
  HorizontalScrollView toolbar=new HorizontalScrollView(this);bar=new LinearLayout(this);toolbar.addView(bar);root.addView(toolbar);
  if(kind.equals("pdf")){
   bar.addView(button("‹",()->jump(page-1)));bar.addView(button("Страница",this::pageDialog));bar.addView(button("›",()->jump(page+1)));
   bar.addView(button("★",this::toggleBookmark));bar.addView(button("Закладки",this::bookmarks));bar.addView(button("Текст / страница",()->{textMode=!textMode;loadPage();}));
   
  }
  if(!kind.equals("txt")){bar.addView(button("Править текст",this::editCurrentText));bar.addView(button("ИИ",this::sendPassage));}
  if(kind.equals("image"))bar.addView(button("Текст / фото",()->{textMode=!textMode;if(textMode)showText(currentText.isEmpty()?"Нажми «Распознать текст» для чтения изображения.":currentText);else{scroll.setVisibility(View.GONE);picture.setVisibility(View.VISIBLE);}}));
  {bar.addView(button("А−",()->font(-2)));bar.addView(button("А+",()->font(2)));bar.addView(button("Тема",()->{theme=(theme+1)%3;prefs.edit().putInt("theme",theme).apply();colors();}));}
  if(!kind.equals("txt")){bar.addView(button("−",()->picture.changeZoom(0.8f)));bar.addView(button("+",()->picture.changeZoom(1.25f)));}
  setContentView(root);colors();
  if(kind.equals("txt")){textMode=true;restoring=true;showText("");io.execute(()->{try{String text=store.pageText(id,0);runOnUiThread(()->{if(!isDestroyed()){showText(text);restoreScroll();}});}catch(Exception e){error(e);}});}
  else if(kind.equals("image"))loadImage();else loadPage();
 }
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 private Button button(String text,Runnable fn){Button b=new Button(this);b.setAllCaps(false);b.setText(text);b.setOnClickListener(v->fn.run());return b;}
 private void colors(){
  int bg=theme==0?0xff111b15:theme==1?0xfffaf8f3:0xffefe2c6,fg=theme==0?0xffe8eee3:0xff29251f;
  root.setBackgroundColor(bg);title.setTextColor(fg);status.setTextColor(fg);prose.setTextColor(fg);prose.setTextSize(font);getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
  if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(theme==0?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
 }
 private void font(int delta){font=Math.max(14,Math.min(34,font+delta));prefs.edit().putInt("font",font).apply();prose.setTextSize(font);if(!kind.equals("txt")&&!textMode){textMode=true;if(kind.equals("pdf"))loadPage();else showText(currentText);}}
 private void showText(String text){picture.setVisibility(View.GONE);scroll.setVisibility(View.VISIBLE);prose.setText(text);colors();if(kind.equals("txt"))status.setText("Режим чтения • позиция сохраняется автоматически");}
 private final Runnable saveScroll=()->{
  if(scroll==null||prose==null||!kind.equals("txt"))return;int max=Math.max(0,prose.getHeight()-scroll.getHeight());float ratio=max==0?0:Math.max(0,Math.min(1,(float)scroll.getScrollY()/max));prefs.edit().putFloat(id+":scroll",ratio).apply();
 };
 private void restoreScroll(){restoring=true;scroll.post(()->{scroll.scrollTo(0,(int)(prefs.getFloat(id+":scroll",0)*Math.max(0,prose.getHeight()-scroll.getHeight())));restoring=false;});}
 private void jump(int next){if(next<0||next>=total)return;page=next;prefs.edit().putInt(id+":page",page).apply();loadPage();}
 private void pageDialog(){
  EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setText(String.valueOf(page+1));
  new AlertDialog.Builder(this).setTitle("Страница 1–"+total).setView(input).setPositiveButton("Перейти",(d,w)->{try{int n=Integer.parseInt(input.getText().toString());if(n<1||n>total)throw new NumberFormatException();jump(n-1);}catch(NumberFormatException e){Toast.makeText(this,"Укажи номер от 1 до "+total,Toast.LENGTH_SHORT).show();}}).setNegativeButton("Отмена",null).show();
 }
 private Set<String> marks(){return new HashSet<>(prefs.getStringSet(id+":marks",Collections.emptySet()));}
 private void toggleBookmark(){Set<String> marks=marks();String n=String.valueOf(page);boolean added=marks.add(n);if(!added)marks.remove(n);prefs.edit().putStringSet(id+":marks",marks).apply();Toast.makeText(this,added?"Закладка добавлена":"Закладка удалена",Toast.LENGTH_SHORT).show();}
 private void bookmarks(){
  ArrayList<Integer> numbers=new ArrayList<>();for(String n:marks())try{numbers.add(Integer.parseInt(n));}catch(Exception ignored){}Collections.sort(numbers);
  if(numbers.isEmpty()){Toast.makeText(this,"Нажми ★ на нужной странице",Toast.LENGTH_SHORT).show();return;}
  String[] labels=new String[numbers.size()];for(int i=0;i<labels.length;i++)labels[i]="Страница "+(numbers.get(i)+1);
  new AlertDialog.Builder(this).setTitle("Закладки").setItems(labels,(d,w)->jump(numbers.get(w))).setNegativeButton("Закрыть",null).show();
 }
 private void loadPage(){
  currentText="";final int token=generation.incrementAndGet(),wanted=page;status.setText("Страница "+(wanted+1)+" из "+total+" • загрузка…");
  io.execute(()->{
   try{
    if(token!=generation.get())return;
    if(renderer==null){descriptor=ParcelFileDescriptor.open(store.original(id),ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);}
    String text=store.pageText(id,wanted);boolean recognized=store.isOcr(id,wanted);Bitmap bitmap=null;
    if(!textMode){try(PdfRenderer.Page p=renderer.openPage(wanted)){
     float scale=Math.min(2000f/p.getWidth(),(float)Math.sqrt(8000000d/((double)p.getWidth()*p.getHeight())));
     bitmap=Bitmap.createBitmap(Math.max(1,Math.round(p.getWidth()*scale)),Math.max(1,Math.round(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    }}
    final Bitmap rendered=bitmap;runOnUiThread(()->{
     if(isDestroyed()||token!=generation.get()){if(rendered!=null)rendered.recycle();return;}
     currentText=text;if(textMode)showText(text.trim().isEmpty()?"На этой странице нет извлечённого текста. Нажми «Распознать текст» или переключись на просмотр страницы.":text);
     else{scroll.setVisibility(View.GONE);picture.setVisibility(View.VISIBLE);picture.setBitmap(rendered);}
     status.setText("Страница "+(wanted+1)+" из "+total+(recognized?" • OCR: проверь текст":"")+(book.optString("warning").isEmpty()?"":" • "+book.optString("warning")));
    });
   }catch(Throwable e){error(e);}
  });
 }
 private void loadImage(){
  io.execute(()->{try{
   Bitmap b=OcrEngine.decodeImage(store.original(id));String text=store.pageText(id,0);
   final Bitmap image=b;runOnUiThread(()->{if(!isDestroyed()){picture.setBitmap(image);currentText=text;status.setText(text.isEmpty()?"Изображение • масштаб двумя пальцами":"Изображение • текст OCR сохранён");}});
  }catch(Throwable e){error(e);}});
 }
 private void recognize(){
  if(ocrBusy)return;final int wanted=page;ocrBusy=true;
  ProgressDialog progress=new ProgressDialog(this);progress.setMessage("Распознаю текст на устройстве…");progress.setCancelable(false);activeDialog=progress;progress.show();
  io.execute(()->{try{
   String text=OcrEngine.recognize(this,store,id,wanted);
   runOnUiThread(()->{ocrBusy=false;if(!isDestroyed()&&!isFinishing()){progress.dismiss();if(text.trim().isEmpty())new AlertDialog.Builder(this).setMessage("Текст не найден. Попробуй более чёткое изображение с печатным текстом.").setPositiveButton("Понятно",null).show();else reviewText(wanted,text);}});
  }catch(Throwable e){runOnUiThread(()->{ocrBusy=false;if(!isDestroyed()&&!isFinishing()){progress.dismiss();error(e);}});}});
 }
 private void editCurrentText(){if(currentText.trim().isEmpty()){Toast.makeText(this,"Сначала распознай текст страницы.",Toast.LENGTH_SHORT).show();return;}reviewText(page,currentText);}
 private void reviewText(int wanted,String text){
  EditText input=new EditText(this);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setText(text);input.setMinLines(5);input.setMaxLines(12);
  ScrollView container=new ScrollView(this);container.addView(input);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Текст OCR — проверь ошибки").setView(container).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();
  activeDialog=dialog;dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   String value=input.getText().toString().trim();if(value.isEmpty()){input.setError("Текст пуст");return;}
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
   io.execute(()->{try{store.saveOcr(id,wanted,value);runOnUiThread(()->{if(!isDestroyed()&&!isFinishing()){dialog.dismiss();if(page==wanted){currentText=value;textMode=true;showText(value);status.setText("Текст OCR сохранён • доступен поиску и ИИ");}}});}
   catch(Throwable e){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing()){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);input.setError(e.getMessage());}});}});
  }));dialog.show();
 }
 private void sendPassage(){
  if(currentText.trim().isEmpty()){Toast.makeText(this,"На странице нет текста для ИИ. Нажми «Распознать текст».",Toast.LENGTH_LONG).show();return;}
  String text=currentText.substring(0,Math.min(currentText.length(),6500));Intent result=new Intent();result.putExtra("text",text);result.putExtra("source",(textMode?"Текст страницы (OCR может содержать ошибки): ":"")+book.optString("name")+", страница "+(page+1)+(text.length()<currentText.length()?" (фрагмент)":""));setResult(RESULT_OK,result);finish();
 }
 private void error(Throwable e){runOnUiThread(()->{if(!isDestroyed()){status.setText("Не удалось открыть: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}});}
 @Override protected void onPause(){if(kind!=null&&kind.equals("txt")&&!restoring)saveScroll.run();super.onPause();}
 @Override protected void onDestroy(){
  if(activeDialog!=null)activeDialog.dismiss();handler.removeCallbacksAndMessages(null);generation.incrementAndGet();
  io.execute(()->{if(renderer!=null)renderer.close();else if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}});
  io.shutdown();super.onDestroy();
 }
}

