package ru.ustavy.offline;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;

/** Fully local recognition: bundled language data, private originals, no network. */
public final class OcrEngine {
 private static final Object DATA_LOCK=new Object();
 public static String recognize(Context context,BookStore store,String id,int page)throws Exception{
  String kind=store.get(id).getString("kind");Bitmap image;
  if(kind.equals("pdf")){
   try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(store.original(id),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page p=renderer.openPage(page)){
    float scale=Math.min(3f,(float)Math.sqrt(10000000d/((double)p.getWidth()*p.getHeight())));
    image=Bitmap.createBitmap(Math.max(1,Math.round(p.getWidth()*scale)),Math.max(1,Math.round(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
    image.eraseColor(Color.WHITE);p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
   }
  }else if(kind.equals("image"))image=decodeImage(store.original(id));
  else throw new IOException("Для распознавания открой PDF или изображение.");
  try{return recognizeBitmap(context,image);}finally{image.recycle();}
 }
 static Bitmap decodeImage(File file)throws IOException{
  BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.toString(),options);
  options.inSampleSize=1;while((long)options.outWidth*options.outHeight/((long)options.inSampleSize*options.inSampleSize)>10000000)options.inSampleSize*=2;
  options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(file.toString(),options);if(bitmap==null)throw new IOException("Изображение не поддерживается.");
  try{
   int orientation=new ExifInterface(file.toString()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();
   switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:break;}
   if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
  }catch(IOException ignored){}
  return bitmap;
 }
 static String recognizeBitmap(Context context,Bitmap image)throws Exception{
  File root=new File(context.getFilesDir(),"ocr"),data=new File(root,"tessdata");
  synchronized(DATA_LOCK){
   if(!data.isDirectory()&&!data.mkdirs())throw new IOException("Не удалось подготовить распознавание.");
   for(String lang:new String[]{"rus","eng"}){
    File file=new File(data,lang+".traineddata");long size=lang.equals("rus")?3861738:4113088;
    if(file.length()==size)continue;File temp=new File(data,lang+".tmp");
    try(InputStream in=context.getAssets().open("tessdata/"+file.getName());FileOutputStream out=new FileOutputStream(temp)){
     byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.getFD().sync();
    }
    if(temp.length()!=size||!temp.renameTo(file))throw new IOException("Не удалось сохранить язык распознавания.");
   }
  }
  TessBaseAPI tess=new TessBaseAPI();try{
   if(!tess.init(root.getAbsolutePath(),"rus+eng"))throw new IOException("Не удалось запустить распознавание.");
   tess.setImage(image);String text=tess.getUTF8Text();return text==null?"":text.trim();
  }finally{tess.recycle();}
 }
}
