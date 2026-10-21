package ru.ustavy.offline;
import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** A single durable local notebook shared by WebView articles and native books. */
public final class NoteStore {
 private static final Object LOCK=new Object();private final File file;
 public NoteStore(Context c){file=new File(c.getFilesDir(),"notes.json");}
 public JSONArray list()throws Exception{synchronized(LOCK){return file.exists()?new JSONArray(BookStore.read(file,12*1024*1024)):new JSONArray();}}
 public JSONObject save(JSONObject input)throws Exception{synchronized(LOCK){
  String quote=input.optString("quote").trim(),comment=input.optString("comment");
  if(quote.isEmpty()||quote.length()>10000||comment.length()>5000)throw new IOException("Цитата — до 10 000, заметка — до 5 000 символов.");
  JSONArray notes=list(),next=new JSONArray();String id=input.optString("id");boolean found=false;
  if(id.isEmpty())id=UUID.randomUUID().toString();else if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Неверная заметка.");
  JSONObject note=new JSONObject();for(String key:new String[]{"source","bookId","articleId"}){String value=input.optString(key);if(value.length()>500)throw new IOException("Слишком длинный источник.");note.put(key,value);}
  note.put("id",id);note.put("quote",quote);note.put("comment",comment);note.put("page",Math.max(0,input.optInt("page")));note.put("start",Math.max(0,input.optInt("start")));note.put("created",input.optLong("created",System.currentTimeMillis()));
  for(int i=0;i<notes.length();i++){JSONObject n=notes.getJSONObject(i);if(n.getString("id").equals(id)){next.put(note);found=true;}else next.put(n);}if(!found)next.put(note);
  if(next.length()>2000)throw new IOException("В блокноте уже 2000 заметок.");write(next);return note;
 }}
 public void delete(String id)throws Exception{synchronized(LOCK){JSONArray notes=list(),next=new JSONArray();for(int i=0;i<notes.length();i++){JSONObject n=notes.getJSONObject(i);if(!n.getString("id").equals(id))next.put(n);}write(next);}}
 private void write(JSONArray notes)throws Exception{
  String text=notes.toString();if(text.length()>2000000)throw new IOException("Блокнот заполнен.");File tmp=new File(file+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}if(!tmp.renameTo(file))throw new IOException("Не удалось сохранить заметки.");
 }
}
