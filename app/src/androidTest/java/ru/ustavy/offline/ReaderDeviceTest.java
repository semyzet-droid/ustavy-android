package ru.ustavy.offline;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderDeviceTest {
 private Context context;private BookStore store;
 @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new BookStore(context);}
 private byte[] pdf(boolean scanned)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();PdfDocument pdf=new PdfDocument();
  for(int n=0;n<2;n++){PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(200,300,n+1).create());Canvas canvas=page.getCanvas();
   canvas.drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(n==0?Color.BLUE:Color.GREEN);canvas.drawRect(10,10,90,90,paint);
   if(!scanned){paint.setColor(Color.BLACK);paint.setTextSize(18);canvas.drawText(n==0?"Alpha duty":"Beta book",10,140,paint);}pdf.finishPage(page);
  }pdf.writeTo(bytes);pdf.close();return bytes.toByteArray();
 }
 @Test public void pdfKeepsIllustrationsExtractsTextAndResumesPage()throws Exception{
  JSONObject book=store.importBook(new ByteArrayInputStream(pdf(false)),"Illustrated.pdf","application/pdf");String id=book.getString("id");
  assertEquals(2,book.getInt("pages"));assertTrue(store.searchable(id).getString("text").contains("Alpha"));
  try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(store.original(id),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(0)){
   Bitmap image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);assertEquals(Color.BLUE,image.getPixel(20,20));image.recycle();
  }
  Intent intent=new Intent(context,BookActivity.class).putExtra("bookId",id);
  try(ActivityScenario<BookActivity> activity=ActivityScenario.launch(intent)){
   awaitPage(activity,Color.BLUE);
   activity.onActivity(a->findButton(a.getWindow().getDecorView(),"›").performClick());
   awaitPage(activity,Color.GREEN);
   activity.onActivity(a->findButton(a.getWindow().getDecorView(),"★").performClick());
  }
  assertEquals(1,context.getSharedPreferences("reader",0).getInt(id+":page",-1));
  assertTrue(context.getSharedPreferences("reader",0).getStringSet(id+":marks",java.util.Collections.emptySet()).contains("1"));
  try(ActivityScenario<BookActivity> activity=ActivityScenario.launch(intent)){awaitPage(activity,Color.GREEN);}
 }
 @Test public void scansRemainViewableWithoutInventedText()throws Exception{
  JSONObject book=store.importBook(new ByteArrayInputStream(pdf(true)),"Scan.pdf","application/pdf");
  assertEquals(2,book.getInt("pages"));assertNull(store.searchable(book.getString("id")));
  assertFalse(book.getString("warning").isEmpty());
  try(ActivityScenario<BookActivity> activity=ActivityScenario.launch(new Intent(context,BookActivity.class).putExtra("bookId",book.getString("id")))){
   awaitPage(activity,Color.BLUE);
   activity.onActivity(a->findButton(a.getWindow().getDecorView(),"Текст / страница").performClick());
   AtomicBoolean shown=new AtomicBoolean(false);
   for(int i=0;i<100&&!shown.get();i++){activity.onActivity(a->shown.set(hasText(a.getWindow().getDecorView(),"На этой странице нет")));if(!shown.get())Thread.sleep(100);}
   assertTrue(shown.get());
   activity.onActivity(a->findButton(a.getWindow().getDecorView(),"ИИ").performClick());
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,activity.getState());
  }
 }
 @Test public void imagesAndUtf8BooksPersist()throws Exception{
  Bitmap bitmap=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.MAGENTA);ByteArrayOutputStream data=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,data);bitmap.recycle();
  JSONObject image=store.importBook(new ByteArrayInputStream(data.toByteArray()),"Picture.png","image/png");
  assertEquals("image",image.getString("kind"));assertNull(store.searchable(image.getString("id")));
  JSONObject text=store.importBook(new ByteArrayInputStream("Глава 1\nТекст книги: учёт.".getBytes(StandardCharsets.UTF_8)),"Book.txt","text/plain");
  assertTrue(new BookStore(context).pageText(text.getString("id"),0).contains("учёт"));
  assertEquals("txt",new BookStore(context).get(text.getString("id")).getString("kind"));
 }
 @Test public void corruptedPdfIsRejected()throws Exception{
  try{store.importBook(new ByteArrayInputStream("broken PDF".getBytes(StandardCharsets.UTF_8)),"Broken.pdf","application/pdf");fail("Invalid PDF must fail");}catch(Exception expected){}
 }
 private Bitmap printedText(){
  Bitmap bitmap=Bitmap.createBitmap(1600,700,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);
  Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(64);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
  canvas.drawText("УСТАВ ВНУТРЕННЕЙ СЛУЖБЫ",60,150,paint);
  canvas.drawText("Статья 42. Воинская дисциплина.",60,260,paint);
  canvas.drawText("Military service 12345",60,370,paint);return bitmap;
 }
 @Test public void offlineOcrReadsRussianPhotoAndPersistsSearchText()throws Exception{
  Bitmap bitmap=printedText();ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();
  JSONObject book=store.importBook(new ByteArrayInputStream(bytes.toByteArray()),"Текст.png","image/png");String id=book.getString("id");
  String recognized=OcrEngine.recognize(context,store,id,0);
  assertTrue(recognized,recognized.toLowerCase(java.util.Locale.ROOT).contains("дисциплина"));
  assertTrue(recognized,recognized.contains("42"));assertTrue(recognized,recognized.contains("12345"));
  store.saveOcr(id,0,recognized);BookStore reopened=new BookStore(context);
  assertEquals(recognized,reopened.pageText(id,0));assertTrue(reopened.searchable(id).getString("text").contains("12345"));
  reopened.saveOcr(id,0,"Исправленный текст статьи 42");assertEquals("Исправленный текст статьи 42",new BookStore(context).pageText(id,0));
 }
 @Test public void scannedPdfOcrReviewSavesCorrectPageAndSendsActualTextToAi()throws Exception{
  Bitmap bitmap=printedText();PdfDocument pdf=new PdfDocument();
  for(int n=0;n<2;n++){PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(800,350,n+1).create());page.getCanvas().drawColor(Color.WHITE);if(n==1)page.getCanvas().drawBitmap(bitmap,null,new Rect(0,0,800,350),null);pdf.finishPage(page);}
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();pdf.writeTo(bytes);pdf.close();bitmap.recycle();
  JSONObject book=store.importBook(new ByteArrayInputStream(bytes.toByteArray()),"Скан.pdf","application/pdf");String id=book.getString("id");assertNull(store.searchable(id));
  assertEquals("",OcrEngine.recognize(context,store,id,0));
  context.getSharedPreferences("reader",0).edit().putInt(id+":page",1).commit();
  try(ActivityScenario<BookActivity> activity=ActivityScenario.launchActivityForResult(new Intent(context,BookActivity.class).putExtra("bookId",id))){
   activity.onActivity(a->findButton(a.getWindow().getDecorView(),"Распознать текст").performClick());
   // Espresso waits for the main thread; recognition happens on a worker, so await its review dialog.
   boolean ready=false;for(int i=0;i<300&&!ready;i++){
    try{androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Текст OCR — проверь ошибки")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));ready=true;}
    catch(androidx.test.espresso.NoMatchingViewException e){Thread.sleep(100);}
   }
   assertTrue("OCR review must appear",ready);
   androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Сохранить")).perform(androidx.test.espresso.action.ViewActions.click());
   boolean saved=false;for(int i=0;i<100&&!saved;i++){saved=store.isOcr(id,1);if(!saved)Thread.sleep(100);}assertTrue(saved);
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   assertTrue(new BookStore(context).pageText(id,0).trim().isEmpty());assertTrue(store.pageText(id,1).contains("42"));
   assertTrue(store.searchable(id).getString("text").contains("Страница 2"));
   // Wait for the saved result to be displayed before asking AI.
   AtomicBoolean shown=new AtomicBoolean(false);for(int i=0;i<100&&!shown.get();i++){activity.onActivity(a->shown.set(hasText(a.getWindow().getDecorView(),"Текст OCR сохранён")));if(!shown.get())Thread.sleep(100);}
   assertTrue(shown.get());activity.onActivity(a->findButton(a.getWindow().getDecorView(),"ИИ").performClick());
   assertEquals(android.app.Activity.RESULT_OK,activity.getResult().getResultCode());assertTrue(activity.getResult().getResultData().getStringExtra("text").contains("42"));
  }
 }
 private Button findButton(View view,String label){
  if(view instanceof Button&&((Button)view).getText().toString().equals(label))return (Button)view;
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button b=findButton(group.getChildAt(i),label);if(b!=null)return b;}}return null;
 }
 private boolean hasText(View view,String value){
  if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),value))return true;}return false;
 }
 private ZoomPageView findPage(View view){
  if(view instanceof ZoomPageView)return (ZoomPageView)view;
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ZoomPageView b=findPage(group.getChildAt(i));if(b!=null)return b;}}return null;
 }
 private void awaitPage(ActivityScenario<BookActivity> activity,int expected)throws Exception{
  AtomicBoolean ready=new AtomicBoolean(false);
  for(int i=0;i<100&&!ready.get();i++){activity.onActivity(a->{ZoomPageView v=findPage(a.getWindow().getDecorView());Bitmap b=v==null?null:v.getBitmap();if(b!=null&&b.getPixel(b.getWidth()/10,b.getHeight()/15)==expected)ready.set(true);});if(!ready.get())Thread.sleep(100);}
  assertTrue("Rendered page with original illustration must be visible",ready.get());
 }
}

