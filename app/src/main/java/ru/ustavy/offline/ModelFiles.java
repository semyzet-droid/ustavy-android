package ru.ustavy.offline;
import android.content.Context;
import java.io.*;
import java.util.function.BooleanSupplier;
final class ModelFiles {
 static File ensure(Context context,BooleanSupplier cancelled)throws IOException{
  File model=new File(context.getFilesDir(),"model.gguf");if(model.isFile()&&model.length()==1282439264L)return model;
  if(context.getFilesDir().getUsableSpace()<1450000000L)throw new IOException("Освободи не менее 1,5 ГБ для распаковки модели.");File temp=new File(context.getFilesDir(),"model.tmp");
  try(InputStream in=context.getAssets().open("model.gguf");FileOutputStream out=new FileOutputStream(temp)){
   byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("Остановлено.");out.write(buffer,0,n);}out.getFD().sync();
  }catch(IOException e){temp.delete();throw e;}
  if(temp.length()!=1282439264L||cancelled.getAsBoolean()||!temp.renameTo(model)){temp.delete();throw new IOException("Не удалось подготовить модель.");}return model;
 }
}
