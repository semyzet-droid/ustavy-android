package ru.ustavy.offline;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** A durable draft: a stopped or interrupted job is never labelled complete. */
public final class TranslationStore {
 private final File file;public TranslationStore(Context context){file=new File(context.getFilesDir(),"translation.json");}
 public synchronized JSONObject load()throws Exception{return file.exists()?new JSONObject(BookStore.read(file,4*1024*1024)):new JSONObject();}
 public synchronized void save(JSONObject draft)throws Exception{String text=draft.toString();if(text.length()>1000000)throw new IOException("Слишком большой перевод.");File temp=new File(file+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(file))throw new IOException("Не удалось сохранить черновик перевода.");}
 public static void export(String text,OutputStream out)throws IOException{if(text==null||text.trim().isEmpty()||text.length()>300000)throw new IOException("Нужен текст перевода до 300 000 символов.");if(out==null)throw new IOException("Нет доступа к выбранному файлу.");out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}
}
