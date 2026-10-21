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
   if("application/pdf".equals(mime)||lower.endsWith(".pdf"))kind="pdf";
   else if((mime!=null&&mime.startsWith("image/"))||lower.matches(".*\\.(png|jpe?g|webp|bmp)$"))kind="image";
   else if((mime!=null&&mime.startsWith("text/"))||lower.endsWith(".txt"))kind="txt";
   else throw new IOException("Поддерживаются PDF, TXT, JPG, PNG и WebP.");
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
     if(visible==0)warning="PDF содержит сканы или не имеет извлекаемого текста. Страницы и картинки доступны; OCR пока не поддерживается.";
    }catch(Exception e){warning="Страницы доступны для просмотра, но текст извлечь не удалось. Поиск и ИИ по этой книге недоступны.";}
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
  }catch(Throwable e){file.delete();path(id,".index.json").delete();path(id,".json").delete();throw e;}
 }
 public JSONObject textBook(String externalId,String name,String text)throws Exception{
  if(text.length()>2000000)throw new IOException("Слишком длинная книга.");
  String id=UUID.nameUUIDFromBytes(("text:"+externalId).getBytes(StandardCharsets.UTF_8)).toString();
  JSONObject meta=new JSONObject();meta.put("id",id);meta.put("name",name);meta.put("kind","txt");meta.put("pages",1);meta.put("indexedPages",1);meta.put("warning","");meta.put("origin","reader-copy");
  atomic(path(id,".book"),text);JSONArray pages=new JSONArray();pages.put(text);atomic(path(id,".index.json"),pages.toString());atomic(path(id,".json"),meta.toString());return meta;
 }
 public String pageText(String id,int page)throws Exception{
  JSONArray pages=new JSONArray(read(path(id,".index.json"),12*1024*1024));return page>=0&&page<pages.length()?pages.getString(page):"";
 }
 public JSONObject searchable(String id)throws Exception{
  JSONObject meta=get(id);if(meta.getString("kind").equals("image")||meta.optString("origin").equals("reader-copy"))return null;
  JSONArray pages=new JSONArray(read(path(id,".index.json"),12*1024*1024));StringBuilder text=new StringBuilder();
  for(int i=0;i<pages.length();i++){String page=pages.getString(i);if(page.trim().isEmpty())continue;if(meta.getString("kind").equals("pdf"))text.append("\nСтраница ").append(i+1).append("\n");text.append(page).append("\n");}
  if(text.toString().trim().isEmpty())return null;
  JSONObject result=new JSONObject();result.put("id","book-"+id);result.put("bookId",id);result.put("name",meta.getString("name"));result.put("text",text.toString());result.put("source","Файл с телефона");result.put("note","Импортированная книга. "+meta.optString("warning"));return result;
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

