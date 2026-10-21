package ru.ustavy.offline;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Bounded source chunks and complete-chunk checkpoints; no network dependencies. */
public final class TranslationEngine {
 public interface Generator{String generate(String prompt)throws Exception;}
 public interface Progress{void update(String output,int completed,int total)throws Exception;}
 public static boolean supported(String from,String to){return ("ru".equals(from)&&("en".equals(to)||"uk".equals(to)))||("ru".equals(to)&&("en".equals(from)||"uk".equals(from)));}
 public static List<String> chunks(String text){
  ArrayList<String> result=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+600);
   if(end<text.length()){int cut=-1;for(int i=end-1;i>start+200;i--)if(Character.isWhitespace(text.charAt(i))){if(cut<0||text.charAt(i)=='\n')cut=i+1;if(text.charAt(i)=='\n')break;}if(cut>start)end=cut;if(Character.isHighSurrogate(text.charAt(end-1)))end--;}
   result.add(text.substring(start,end));start=end;
  }return result;
 }
 public static String prompt(JSONObject templates,String from,String to,String text)throws Exception{
  if(!supported(from,to))throw new IOException("Выбери русский ↔ английский или русский ↔ украинский.");
  JSONObject pair=templates.getJSONObject(from+"-"+to);StringBuilder prompt=new StringBuilder("<|im_start|>system\n");prompt.append(pair.getString("system")).append("<|im_end|>\n");
  JSONArray examples=pair.getJSONArray("examples");for(int i=0;i<examples.length();i++){JSONArray sample=examples.getJSONArray(i);prompt.append("<|im_start|>user\n").append(sample.getString(0)).append("<|im_end|>\n<|im_start|>assistant\n").append(sample.getString(1)).append("<|im_end|>\n");}
  // Prevent document text from opening chat-template control tokens.
  prompt.append("<|im_start|>user\n").append(text.replace("<|","＜｜").replace("|>","｜＞")).append("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n");return prompt.toString();
 }
 public static String translate(String input,String from,String to,JSONObject templates,Generator generator,BooleanSupplier cancelled,Progress progress)throws Exception{
  if(input==null||input.trim().isEmpty()||input.length()>100000)throw new IOException("Введи текст до 100 000 символов.");if(!supported(from,to))throw new IOException("Неподдерживаемая пара языков.");
  List<String> parts=chunks(input);StringBuilder result=new StringBuilder();for(int i=0;i<parts.size();i++){
   if(cancelled.getAsBoolean())throw new InterruptedIOException("Перевод остановлен. Сохранены только готовые фрагменты.");String part=parts.get(i);int a=0,b=part.length();while(a<b&&Character.isWhitespace(part.charAt(a)))a++;while(b>a&&Character.isWhitespace(part.charAt(b-1)))b--;
   String translated=a==b?"":generator.generate(prompt(templates,from,to,part.substring(a,b))).trim();
   if(cancelled.getAsBoolean())throw new InterruptedIOException("Перевод остановлен. Сохранены только готовые фрагменты.");if(a!=b&&translated.isEmpty())throw new IOException("Модель вернула пустой перевод.");
   result.append(part,0,a).append(translated).append(part.substring(b));if(result.length()>300000)throw new IOException("Результат превышает 300 000 символов.");progress.update(result.toString(),i+1,parts.size());
  }return result.toString();
 }
}
