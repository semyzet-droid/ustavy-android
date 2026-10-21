package ru.ustavy.offline;
import android.text.*;
import android.text.style.ImageSpan;
import android.graphics.drawable.ColorDrawable;
import android.util.*;
import org.xmlpull.v1.XmlPullParser;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.*;

/** Bounded, network-free EPUB / FB2 decoding in publication reading order. */
final class BookFormats {
 final JSONArray pages=new JSONArray(),images=new JSONArray();private final File media;private final Map<String,byte[]> entries=new HashMap<>();private long expanded=0;
 BookFormats(File media){this.media=media;}
 static BookFormats read(File file,String kind,File media)throws Exception{
  BookFormats out=new BookFormats(media);if(kind.equals("epub")||kind.equals("fb2zip"))out.unzip(file);
  if(kind.equals("epub"))out.epub();else if(kind.equals("fb2zip")){
   String name=out.entries.keySet().stream().filter(n->n.toLowerCase(Locale.ROOT).endsWith(".fb2")).sorted().findFirst().orElseThrow(()->new IOException("В ZIP нет FB2."));out.fb2(out.entries.get(name));
  }else{if(file.length()>20*1024*1024)throw new IOException("FB2 должен быть до 20 МБ.");out.fb2(java.nio.file.Files.readAllBytes(file.toPath()));}
  if(out.pages.length()==0)throw new IOException("В книге нет читаемого текста.");return out;
 }
 private void unzip(File file)throws Exception{
  try(ZipInputStream zip=new ZipInputStream(new FileInputStream(file))){ZipEntry entry;int count=0;byte[] buffer=new byte[8192];
   while((entry=zip.getNextEntry())!=null){if(++count>3000)throw new IOException("В архиве слишком много файлов.");if(entry.isDirectory())continue;
    String name=normalize(entry.getName());ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;
    while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>80*1024*1024||bytes.size()+n>20*1024*1024)throw new IOException("Книга слишком велика после распаковки.");bytes.write(buffer,0,n);}entries.put(name,bytes.toByteArray());
   }
  }
 }
 private String normalize(String name)throws IOException{
  if(name.contains("\\")||name.startsWith("/")||name.contains(":"))throw new IOException("Недопустимый путь внутри книги.");String normalized=Paths.get(name).normalize().toString();if(normalized.startsWith(".."))throw new IOException("Недопустимый путь внутри книги.");return normalized;
 }
 private XmlPullParser xml(byte[] data)throws Exception{
  if(data==null)throw new IOException("В книге отсутствует обязательный файл.");XmlPullParser parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(new ByteArrayInputStream(data),null);return parser;
 }
 private String attr(XmlPullParser parser,String name){for(int i=0;i<parser.getAttributeCount();i++)if(parser.getAttributeName(i).equals(name))return parser.getAttributeValue(i);return "";}
 private byte[] entry(String path)throws Exception{byte[] data=entries.get(normalize(path));if(data==null)throw new IOException("В EPUB отсутствует "+path);return data;}
 private void epub()throws Exception{
  XmlPullParser container=xml(entry("META-INF/container.xml"));String opf="";
  for(int event=container.next();event!=XmlPullParser.END_DOCUMENT;event=container.next())if(event==XmlPullParser.START_TAG&&container.getName().equals("rootfile")){opf=attr(container,"full-path");break;}
  if(opf.isEmpty())throw new IOException("Не найдено оглавление EPUB.");String folder=opf.contains("/")?opf.substring(0,opf.lastIndexOf('/')+1):"";
  Map<String,String> manifest=new HashMap<>();ArrayList<String> spine=new ArrayList<>();XmlPullParser parser=xml(entry(opf));
  for(int event=parser.next();event!=XmlPullParser.END_DOCUMENT;event=parser.next())if(event==XmlPullParser.START_TAG){
   if(parser.getName().equals("item"))manifest.put(attr(parser,"id"),attr(parser,"href"));
   if(parser.getName().equals("itemref"))spine.add(attr(parser,"idref"));
  }
  int total=0;for(String id:spine){String href=manifest.get(id);if(href==null)throw new IOException("Повреждён порядок глав EPUB.");String path=normalize(folder+android.net.Uri.decode(href.split("#",2)[0]));
   String html=new String(entry(path),StandardCharsets.UTF_8).replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>","");
   Spanned content=Html.fromHtml(html,Html.FROM_HTML_MODE_COMPACT,source->{ColorDrawable dummy=new ColorDrawable(0);dummy.setBounds(0,0,1,1);return dummy;},null);
   String text=content.toString();total+=text.length();if(total>2000000||pages.length()>=1000)throw new IOException("Текст книги превышает лимит 2 млн символов или 1000 глав.");
   JSONArray pictures=new JSONArray();String dir=path.contains("/")?path.substring(0,path.lastIndexOf('/')+1):"";
   for(ImageSpan span:content.getSpans(0,content.length(),ImageSpan.class)){String source=span.getSource();try{
    String image=normalize(dir+android.net.Uri.decode(source.split("#",2)[0]));String saved=saveImage(entries.get(image));if(saved!=null)pictures.put(new JSONObject().put("pos",content.getSpanStart(span)).put("file",saved));
   }catch(Exception ignored){}}
   appendPages(text,pictures);
  }
 }
 private void appendPages(String text,JSONArray pictures)throws Exception{
  int start=0;while(start<text.length()){
   int end=Math.min(text.length(),start+20000),count=0;
   for(int i=0;i<pictures.length();i++){int at=pictures.getJSONObject(i).getInt("pos");if(at>=start&&at<end&&++count>10){end=at;break;}}
   if(end<text.length()&&end-start>10000){int line=text.lastIndexOf('\n',end-1);if(line>start+10000)end=line+1;}
   if(end>start&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
   if(end<=start)end=Math.min(text.length(),start+1);
   JSONArray local=new JSONArray();for(int i=0;i<pictures.length();i++){JSONObject image=pictures.getJSONObject(i);int at=image.getInt("pos");if(at>=start&&at<end)local.put(new JSONObject().put("pos",at-start).put("file",image.getString("file")));}
   if(pages.length()>=1000)throw new IOException("В книге более 1000 разделов.");pages.put(text.substring(start,end));images.put(local);start=end;
  }
 }
 private String saveImage(byte[] bytes)throws Exception{
  if(bytes==null||bytes.length>12*1024*1024)return null;android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);if(opt.outWidth<=0||opt.outHeight<=0)return null;
  media.mkdirs();String name=UUID.randomUUID()+".img";try(FileOutputStream out=new FileOutputStream(new File(media,name))){out.write(bytes);}return name;
 }
 private void fb2(byte[] data)throws Exception{
  XmlPullParser parser=xml(data);StringBuilder text=new StringBuilder();ArrayList<String[]> references=new ArrayList<>();Map<String,String> binaries=new HashMap<>();int body=0;
  for(int event=parser.next();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
   if(event==XmlPullParser.START_TAG){String tag=parser.getName();if(tag.equals("body")){body++;continue;}
    if(tag.equals("binary")){String id=attr(parser,"id");String encoded=parser.nextText();try{String image=saveImage(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT));if(image!=null)binaries.put(id,image);}catch(Exception ignored){}continue;}
    if(body>0){if(tag.equals("image")){String href=attr(parser,"href");references.add(new String[]{String.valueOf(text.length()),href.startsWith("#")?href.substring(1):href});text.append('\uFFFC');}
     else if(tag.matches("p|title|subtitle|section|stanza|v|empty-line"))text.append('\n');
    }
   }else if(event==XmlPullParser.TEXT&&body>0)text.append(parser.getText());
   else if(event==XmlPullParser.END_TAG){if(parser.getName().equals("body"))body--;else if(body>0&&parser.getName().matches("p|title|subtitle|section|stanza|v"))text.append('\n');}
   if(text.length()>2000000)throw new IOException("Текст FB2 превышает 2 млн символов.");
  }
  if(text.toString().trim().isEmpty())throw new IOException("В FB2 нет текста.");
  JSONArray pictures=new JSONArray();for(String[] ref:references)if(binaries.containsKey(ref[1]))pictures.put(new JSONObject().put("pos",Integer.parseInt(ref[0])).put("file",binaries.get(ref[1])));
  // FB2 is a continuous scroll book; EPUB follows its explicit chapter spine.
  appendPages(text.toString(),pictures);
 }
}
