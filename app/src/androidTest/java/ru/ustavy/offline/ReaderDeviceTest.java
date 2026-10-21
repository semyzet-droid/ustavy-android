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

