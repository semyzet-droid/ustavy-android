package ru.ustavy.offline;
import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.cos.COSName;
import java.io.*;
import java.util.*;

/** Exportable PNG copies; originals and book indexes remain private and unchanged. */
public final class BookImages {
 public static final class Picture {public final File file;public final String label;Picture(File f,String l){file=f;label=l;}}
 private final Context context;private final File dir;private final ArrayList<Picture> pictures=new ArrayList<>();private long bytes=0;
 private BookImages(Context c){context=c;dir=new File(c.getCacheDir(),"book-pictures");dir.mkdirs();File[] old=dir.listFiles();if(old!=null)for(File file:old)if(file.lastModified()<System.currentTimeMillis()-86400000L)file.delete();}
 public static List<Picture> collect(Context c,BookStore store,String id,int page)throws Exception{
  BookImages result=new BookImages(c);String kind=store.get(id).getString("kind");
  if(kind.equals("pdf")){
   try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(store.original(id),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page p=renderer.openPage(page)){
    float scale=Math.min(3f,(float)Math.sqrt(8000000d/((double)p.getWidth()*p.getHeight())));Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(p.getWidth()*scale)),Math.max(1,Math.round(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);try{result.add(bitmap,"Страница целиком");}finally{bitmap.recycle();}
   }
   try(PDDocument document=PDDocument.load(store.original(id))){result.extract(document.getPage(page).getResources(),0,Collections.newSetFromMap(new IdentityHashMap<>()));}
   catch(Exception ignored){} // A valid rendered page is still exportable when embedded objects cannot be decoded.
  }else if(kind.equals("image")){Bitmap bitmap=OcrEngine.decodeImage(store.original(id));try{result.add(bitmap,"Изображение");}finally{bitmap.recycle();}}
  else{for(File file:store.imageFiles(id,page)){if(result.pictures.size()>=64)break;Bitmap bitmap=OcrEngine.decodeImage(file);try{result.add(bitmap,"Иллюстрация "+(result.pictures.size()+1));}finally{bitmap.recycle();}}}
  return result.pictures;
 }
 private void extract(PDResources resources,int depth,Set<PDResources> seen)throws Exception{
  if(resources==null||depth>8||pictures.size()>=64||!seen.add(resources))return;
  for(COSName name:resources.getXObjectNames()){
   if(pictures.size()>=64||bytes>=64*1024*1024)return;
   try{PDXObject object=resources.getXObject(name);
    if(object instanceof PDImageXObject){PDImageXObject source=(PDImageXObject)object;if((long)source.getWidth()*source.getHeight()>12000000)continue;Bitmap bitmap=source.getImage();if(bitmap!=null){try{add(bitmap,"Картинка "+pictures.size());}finally{bitmap.recycle();}}}
    else if(object instanceof PDFormXObject)extract(((PDFormXObject)object).getResources(),depth+1,seen);
   }catch(IOException ignored){}
  }
 }
 private void add(Bitmap bitmap,String label)throws Exception{
  File file=new File(dir,UUID.randomUUID()+".png");try(FileOutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Не удалось подготовить картинку.");}
  if(bytes+file.length()>64*1024*1024){file.delete();return;}bytes+=file.length();pictures.add(new Picture(file,label));
 }
 public static void copy(File file,OutputStream target)throws IOException{
  if(target==null)throw new IOException("Нет доступа к папке сохранения.");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)target.write(buffer,0,n);target.flush();}
 }
}
