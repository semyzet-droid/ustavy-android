package ru.ustavy.offline;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.graphics.BitmapFactory;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Private, durable book originals and bounded search indexes; never uses network access. */
public final class BookStore {
 private final Context context; private final File dir;
 private static final long MAX_FILE=100L*1024*1024;
 public BookStore(Context c){context=c.getApplicationContext();dir=new File(context.getFilesDir(),"books");dir.mkdirs();PDFBoxResourceLoader.init(context);}
 private File path(String id,String suffix){
  if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Некорректный идентификатор книги");
  return new File(dir,id+suffix);
 }
 public JSONObject get(String id)throws Exception{return new JSONObject(read(path(id,".json"),100000));}
 public File original(String id)throws Exception{get(id);return path(id,".book");}
 public JSONArray list(){
  JSONArray list=new JSONArray();File[] files=dir.listFiles((d,n)->n.endsWith(".json")&&!n.endsWith(".index.json"));
  if(files!=null){Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(File f:files)try{
   JSONObject book=new JSONObject(read(f,100000));String id=book.getString("id");
   book.put("position",context.getSharedPreferences("reader",0).getInt(id+":page",0));
   book.put("progress",context.getSharedPreferences("reader",0).getFloat(id+":scroll",0));list.put(book);
  }catch(Exception ignored){}}
  return list;
 }
 public JSONObject importBook(InputStream source,String name,String mime)throws Exception{
  String id=UUID.randomUUID().toString();File file=path(id,".book");
  try{
   copy(source,file,MAX_FILE);String lower=name.toLowerCase(Locale.ROOT),kind;
   if(lower.endsWith(".epub")||"application/epub+zip".equals(mime))kind="epub";
   else if(lower.endsWith(".fb2.zip")||lower.endsWith(".zip"))kind="fb2zip";
   else if(lower.endsWith(".fb2")||"application/x-fictionbook+xml".equals(mime))kind="fb2";
   else if("application/pdf".equals(mime)||lower.endsWith(".pdf"))kind="pdf";
   else if((mime!=null&&mime.startsWith("image/"))||lower.matches(".*\\.(png|jpe?g|webp|bmp)$"))kind="image";
   else if((mime!=null&&mime.startsWith("text/"))||lower.endsWith(".txt"))kind="txt";
   else throw new IOException("Поддерживаются PDF, EPUB, FB2, FB2.ZIP, TXT, JPG, PNG и WebP.");
   JSONObject meta=new JSONObject();meta.put("id",id);meta.put("name",name);meta.put("kind",kind);meta.put("bytes",file.length());
   JSONArray pages=new JSONArray();String warning="";int total=1;
   if(kind.equals("pdf")){
    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){total=renderer.getPageCount();if(total<1)throw new IOException("В PDF нет страниц.");}
    try(PDDocument document=PDDocument.load(file)){
     PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);int used=0,visible=0;
     for(int p=1;p<=Math.min(total,1000);p++){
      stripper.setStartPage(p);stripper.setEndPage(p);String text=stripper.getText(document);
      if(used+text.length()>1000000){warning="Поисковый текст ограничен первыми "+pages.length()+" страницами. Все страницы доступны для просмотра.";break;}
      pages.put(text);used+=text.length();visible+=text.trim().length();
     }
     if(total>pages.length()&&warning.isEmpty())warning="Для поиска обработаны первые "+pages.length()+" страниц. Просмотр доступен для всей книги.";
     if(visible==0)warning="PDF содержит сканы или не имеет извлекаемого текста. Страницы и картинки доступны; Нажми «Распознать текст» на нужной странице.";
    }catch(Exception e){warning="Страницы доступны для просмотра, но текст извлечь не удалось. Для скана нажми «Распознать текст».";}
   }else if(kind.equals("epub")||kind.equals("fb2")||kind.equals("fb2zip")){
    BookFormats decoded=BookFormats.read(file,kind,path(id,"-media"));pages=decoded.pages;total=pages.length();atomic(path(id,".images.json"),decoded.images.toString());if(kind.equals("fb2zip")){kind="fb2";meta.put("kind",kind);}
   }else if(kind.equals("image")){
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.toString(),bounds);
    if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Не удалось прочитать изображение.");
   }else{
    String text=read(file,5*1024*1024).replace("\uFEFF","");
    if(text.trim().isEmpty()||text.indexOf('\uFFFD')>=0||text.indexOf('\0')>=0)throw new IOException("Нужен непустой TXT в UTF-8.");
    pages.put(text);
   }
   meta.put("pages",total);meta.put("indexedPages",pages.length());meta.put("warning",warning);
   atomic(path(id,".index.json"),pages.toString());atomic(path(id,".json"),meta.toString());return meta;
  }catch(Throwable e){file.delete();path(id,".index.json").delete();path(id,".json").delete();path(id,".images.json").delete();File media=path(id,"-media");File[] images=media.listFiles();if(images!=null)for(File image:images)image.delete();media.delete();throw e;}
 }
 public JSONObject textBook(String externalId,String name,String text)throws Exception{
  if(text.length()>2000000)throw new IOException("Слишком длинная книга.");
  String id=UUID.nameUUIDFromBytes(("text:"+externalId).getBytes(StandardCharsets.UTF_8)).toString();
  JSONObject meta=new JSONObject();meta.put("id",id);meta.put("name",name);meta.put("kind","txt");meta.put("pages",1);meta.put("indexedPages",1);meta.put("warning","");meta.put("origin","reader-copy");
  atomic(path(id,".book"),text);JSONArray pages=new JSONArray();pages.put(text);atomic(path(id,".index.json"),pages.toString());atomic(path(id,".json"),meta.toString());return meta;
 }
 public android.text.SpannableString illustrated(String id,int page,String text,int width)throws Exception{
  android.text.SpannableString result=new android.text.SpannableString(text);File manifest=path(id,".images.json");if(!manifest.exists())return result;
  JSONArray all=new JSONArray(read(manifest,12*1024*1024));JSONArray images=all.optJSONArray(page);if(images==null)return result;
  for(int i=0;i<images.length();i++){JSONObject image=images.getJSONObject(i);String name=image.getString("file");int at=image.getInt("pos");if(!name.matches("[a-f0-9-]{36}\\.img")||at<0||at>=text.length()||text.charAt(at)!='\uFFFC')continue;
   try{android.graphics.Bitmap bitmap=OcrEngine.decodeImage(new File(path(id,"-media"),name));
    float scale=Math.min(1,Math.min(Math.min(1000,Math.max(100,width))/(float)bitmap.getWidth(),2000f/bitmap.getHeight()));
    int w=Math.max(1,Math.round(bitmap.getWidth()*scale)),h=Math.max(1,Math.round(bitmap.getHeight()*scale));
    if(w!=bitmap.getWidth()||h!=bitmap.getHeight()){android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(bitmap,w,h,true);if(small!=bitmap)bitmap.recycle();bitmap=small;}
    android.graphics.drawable.BitmapDrawable drawable=new android.graphics.drawable.BitmapDrawable(context.getResources(),bitmap);drawable.setBounds(0,0,w,h);
    result.setSpan(new android.text.style.ImageSpan(drawable),at,at+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
   }catch(IOException ignored){}
  }return result;
 }
 public String pageText(String id,int page)throws Exception{
  JSONObject ocr=ocrPages(id);if(ocr.has(String.valueOf(page)))return ocr.getString(String.valueOf(page));
  JSONArray pages=new JSONArray(read(path(id,".index.json"),12*1024*1024));return page>=0&&page<pages.length()?pages.getString(page):"";
 }
 private JSONObject ocrPages(String id)throws Exception{File file=path(id,".ocr.json");return file.exists()?new JSONObject(read(file,12*1024*1024)):new JSONObject();}
 public boolean isOcr(String id,int page)throws Exception{return ocrPages(id).has(String.valueOf(page));}
 public synchronized void saveOcr(String id,int page,String text)throws Exception{
  JSONObject meta=get(id);if(page<0||page>=meta.optInt("pages",1))throw new IOException("Нет такой страницы.");
  text=text.trim();if(text.isEmpty())throw new IOException("Нет текста для сохранения.");
  if(text.length()>100000)throw new IOException("Текст страницы превышает 100 000 символов.");
  JSONObject ocr=ocrPages(id);ocr.put(String.valueOf(page),text);String encoded=ocr.toString();
  if(encoded.length()>2000000)throw new IOException("Достигнут лимит распознанного текста книги.");
  atomic(path(id,".ocr.json"),encoded);
 }
 public JSONObject searchable(String id)throws Exception{
  JSONObject meta=get(id);if(meta.optString("origin").equals("reader-copy"))return null;
  JSONArray pages=new JSONArray(read(path(id,".index.json"),12*1024*1024));TreeMap<Integer,String> merged=new TreeMap<>();
  for(int i=0;i<pages.length();i++)merged.put(i,pages.getString(i));
  JSONObject ocr=ocrPages(id);Iterator<String> keys=ocr.keys();while(keys.hasNext()){String key=keys.next();merged.put(Integer.parseInt(key),ocr.getString(key));}
  StringBuilder text=new StringBuilder();for(Map.Entry<Integer,String> entry:merged.entrySet()){
   String value=entry.getValue();if(value.trim().isEmpty())continue;if(meta.getString("kind").equals("pdf"))text.append("\nСтраница ").append(entry.getKey()+1).append("\n");text.append(value).append("\n");
  }
  if(text.toString().trim().isEmpty())return null;
  JSONObject result=new JSONObject();result.put("id","book-"+id);result.put("bookId",id);result.put("name",meta.getString("name"));result.put("text",text.toString());result.put("source","Файл с телефона");result.put("note","Импортированная книга. "+(ocr.length()>0?"Есть текст OCR: возможны ошибки. ":"")+meta.optString("warning"));return result;
 }
 private void atomic(File to,String text)throws Exception{
  File tmp=new File(to+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  if(!tmp.renameTo(to))throw new IOException("Не удалось сохранить книгу.");
 }
 private static void copy(InputStream source,File file,long limit)throws IOException{
  if(source==null)throw new IOException("Нет доступа к файлу.");
  try(InputStream in=source;FileOutputStream out=new FileOutputStream(file)){byte[] buf=new byte[65536];long size=0;int n;while((n=in.read(buf))!=-1){size+=n;if(size>limit)throw new IOException("Размер файла превышает 100 МБ.");out.write(buf,0,n);}out.getFD().sync();}
 }
 public static String read(File file,int limit)throws IOException{
  try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой текст.");out.write(buf,0,n);}return out.toString("UTF-8");}
 }
}

