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
   // Inspect the real review window on the main thread; transient progress windows may have no focus.
   AtomicBoolean reviewed=new AtomicBoolean(false);
   for(int i=0;i<300&&!reviewed.get();i++){
    activity.onActivity(a->{try{
     java.lang.reflect.Field field=BookActivity.class.getDeclaredField("activeDialog");field.setAccessible(true);
     android.app.Dialog dialog=(android.app.Dialog)field.get(a);
     if(dialog instanceof android.app.AlertDialog&&dialog.isShowing()&&hasText(dialog.getWindow().getDecorView(),"Текст OCR — проверь ошибки")){
      android.app.AlertDialog review=(android.app.AlertDialog)dialog;
      review.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();reviewed.set(true);
     }
    }catch(ReflectiveOperationException e){throw new AssertionError(e);}});
    if(!reviewed.get())Thread.sleep(100);
   }
   assertTrue("OCR review must appear and allow saving",reviewed.get());
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
 @Test public void wallpaperIsCopiedAndInvalidReplacementKeepsSavedBackground()throws Exception{
  Bitmap bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();
  Appearance.importWallpaper(context,new ByteArrayInputStream(bytes.toByteArray()));
  Appearance.save(context,"custom","#faf0cc",40);
  try{
   assertEquals("custom",Appearance.scheme(context));assertTrue(Appearance.light(context));
   JSONObject payload=Appearance.payload(context);assertEquals(40,payload.getInt("strength"));assertTrue(payload.getString("image").startsWith("data:image/jpeg;base64,"));
   long size=Appearance.wallpaper(context).length();
   try{Appearance.importWallpaper(context,new ByteArrayInputStream(new byte[]{1,2,3}));fail("Broken image must fail");}catch(IOException expected){}
   assertEquals(size,Appearance.wallpaper(context).length());
   Bitmap stored=BitmapFactory.decodeFile(Appearance.wallpaper(context).toString());assertTrue(Color.blue(stored.getPixel(20,20))>240);stored.recycle();
  }finally{Appearance.wallpaper(context).delete();Appearance.prefs(context).edit().clear().commit();}
 }
 private byte[] zip(java.util.Map<String,byte[]> files)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){for(java.util.Map.Entry<String,byte[]> file:files.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}}return bytes.toByteArray();
 }
 private byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}
 private byte[] blueImage(){Bitmap image=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);image.recycle();return bytes.toByteArray();}
 @Test public void epubUsesSpineOrderAndKeepsInlinePictures()throws Exception{
  java.util.Map<String,byte[]> files=new java.util.LinkedHashMap<>();files.put("META-INF/container.xml",utf8("<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>"));
  files.put("OPS/book.opf",utf8("<package><manifest><item id='second' href='second.xhtml'/><item id='first' href='first.xhtml'/></manifest><spine><itemref idref='first'/><itemref idref='second'/></spine></package>"));
  files.put("OPS/first.xhtml",utf8("<html><body><h1>Первая глава</h1><p>Воинская служба.</p><img src='image.png'/></body></html>"));files.put("OPS/second.xhtml",utf8("<html><body><p>Вторая глава</p></body></html>"));files.put("OPS/image.png",blueImage());
  JSONObject book=store.importBook(new ByteArrayInputStream(zip(files)),"Книга.epub","application/epub+zip");String id=book.getString("id");assertEquals(2,book.getInt("pages"));assertTrue(store.pageText(id,0).contains("Первая"));assertTrue(store.pageText(id,1).contains("Вторая"));
  android.text.SpannableString text=store.illustrated(id,0,store.pageText(id,0),300);android.text.style.ImageSpan[] images=text.getSpans(0,text.length(),android.text.style.ImageSpan.class);assertEquals(1,images.length);
  android.graphics.drawable.BitmapDrawable drawable=(android.graphics.drawable.BitmapDrawable)images[0].getDrawable();assertEquals(Color.BLUE,drawable.getBitmap().getPixel(10,10));
  context.getSharedPreferences("reader",0).edit().putInt(id+":page",1).commit();try(ActivityScenario<BookActivity> activity=ActivityScenario.launch(new Intent(context,BookActivity.class).putExtra("bookId",id))){awaitText(activity,"Вторая глава");}
 }
 @Test public void fb2ZipKeepsRussianTextPicturesAndRejectsTraversal()throws Exception{
  String xml="<?xml version='1.0' encoding='UTF-8'?><FictionBook xmlns:l='http://www.w3.org/1999/xlink'><body><section><title><p>Глава первая</p></title><p>Русская книга для чтения.</p><image l:href='#photo'/></section></body><binary id='photo' content-type='image/png'>"+android.util.Base64.encodeToString(blueImage(),android.util.Base64.NO_WRAP)+"</binary></FictionBook>";
  JSONObject book=store.importBook(new ByteArrayInputStream(zip(java.util.Collections.singletonMap("book.fb2",utf8(xml)))),"Книга.fb2.zip","application/zip");String id=book.getString("id");assertEquals("fb2",book.getString("kind"));assertTrue(store.pageText(id,0).contains("Русская книга"));
  android.text.SpannableString text=store.illustrated(id,0,store.pageText(id,0),300);assertEquals(1,text.getSpans(0,text.length(),android.text.style.ImageSpan.class).length);
  try{store.importBook(new ByteArrayInputStream(zip(java.util.Collections.singletonMap("../escape.fb2",utf8(xml)))),"Bad.fb2.zip","application/zip");fail("Traversal must fail");}catch(IOException expected){}
 }
 @Test public void nativeSelectedQuoteSavesCommentAndHighlightsSameSentence()throws Exception{
  JSONObject book=store.importBook(new ByteArrayInputStream(utf8("Первая строка. Военнослужащий обязан знать устав.")),"Заметки.txt","text/plain");String id=book.getString("id");
  try(ActivityScenario<BookActivity> activity=ActivityScenario.launch(new Intent(context,BookActivity.class).putExtra("bookId",id))){
   awaitText(activity,"Военнослужащий");activity.onActivity(a->{try{
    java.lang.reflect.Field field=BookActivity.class.getDeclaredField("prose");field.setAccessible(true);TextView text=(TextView)field.get(a);int start=text.getText().toString().indexOf("Военнослужащий");android.text.Selection.setSelection((android.text.Spannable)text.getText(),start,text.length());
    android.widget.PopupMenu menu=new android.widget.PopupMenu(a,text);MenuItem item=menu.getMenu().add(0,707,0,"В заметки");text.getCustomSelectionActionModeCallback().onActionItemClicked(null,item);
   }catch(ReflectiveOperationException e){throw new AssertionError(e);}});
   activity.onActivity(a->{try{java.lang.reflect.Field field=BookActivity.class.getDeclaredField("activeDialog");field.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(a);EditText input=findEdit(dialog.getWindow().getDecorView());input.setText("Повторить завтра");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();}catch(ReflectiveOperationException e){throw new AssertionError(e);}});
  }
  NoteStore notebook=new NoteStore(context);JSONObject saved=null;JSONArray notes=notebook.list();for(int i=0;i<notes.length();i++)if(notes.getJSONObject(i).optString("bookId").equals(id))saved=notes.getJSONObject(i);
  assertNotNull(saved);assertEquals("Повторить завтра",saved.getString("comment"));assertTrue(saved.getString("quote").startsWith("Военнослужащий"));
  String noteId=saved.getString("id");saved.put("comment","Исправлено");notebook.save(saved);assertTrue(new NoteStore(context).list().toString().contains("Исправлено"));notebook.delete(noteId);assertFalse(new NoteStore(context).list().toString().contains(noteId));
 }
 private EditText findEdit(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findEdit(group.getChildAt(i));if(found!=null)return found;}}return null;}
 private void awaitText(ActivityScenario<BookActivity> activity,String text)throws Exception{AtomicBoolean ready=new AtomicBoolean(false);for(int i=0;i<100&&!ready.get();i++){activity.onActivity(a->ready.set(hasText(a.getWindow().getDecorView(),text)));if(!ready.get())Thread.sleep(100);}assertTrue("Book text must be readable",ready.get());}
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

