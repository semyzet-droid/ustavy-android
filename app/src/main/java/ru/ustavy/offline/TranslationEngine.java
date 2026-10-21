package ru.ustavy.offline;
import java.io.*;
import java.util.*;
import org.json.*;

/** Bounded source chunks and complete-chunk checkpoints; no network dependencies. */
public final class TranslationEngine {
 public static boolean supported(String from,String to){return ("ru".equals(from)&&("en".equals(to)||"uk".equals(to)))||("ru".equals(to)&&("en".equals(from)||"uk".equals(from)));}
 public static List<String> chunks(String text){
  ArrayList<String> result=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+600);
   if(end<text.length()){int cut=-1;for(int i=end-1;i>start+200;i--)if(Character.isWhitespace(text.charAt(i))){if(cut<0||text.charAt(i)=='\n')cut=i+1;if(text.charAt(i)=='\n')break;}if(cut>start)end=cut;if(Character.isHighSurrogate(text.charAt(end-1)))end--;}
   result.add(text.substring(start,end));start=end;
  }return result;
 }
 public static JSONObject checkpoint(JSONObject previous,JSONObject event)throws Exception{
  if(!"running".equals(previous.optString("status"))||!previous.optString("id").equals(event.optString("id")))throw new IOException("Устаревший фрагмент перевода.");
  int completed=event.optInt("completed",-1),total=previous.optInt("total");String status=event.optString("status"),output=event.optString("output");
  if(completed<previous.optInt("completed")||completed>total||output.length()>300000||!java.util.Arrays.asList("running","done","error").contains(status))throw new IOException("Неверное состояние перевода.");
  if(status.equals("done")&&completed!=total)throw new IOException("Переведён не весь исходный текст.");
  JSONObject next=new JSONObject(previous.toString());next.put("status",status);next.put("output",output);next.put("completed",completed);next.put("message",event.optString("message"));return next;
 }
 public static JSONObject stopped(JSONObject previous)throws Exception{JSONObject next=new JSONObject(previous.toString());next.put("status","cancelled");next.put("message","Остановлено. Результат неполный; сохранены только готовые фрагменты.");return next;}
}
