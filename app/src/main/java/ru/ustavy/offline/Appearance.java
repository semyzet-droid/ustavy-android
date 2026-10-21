package ru.ustavy.offline;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.util.Base64;
import android.view.View;
import java.io.*;
import org.json.JSONObject;

/** Shared local appearance for the home screen and native book reader. */
public final class Appearance {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("appearance",0);}
 static String scheme(Context c){return prefs(c).getString("scheme","dark");}
 static int background(Context c){switch(scheme(c)){
  case "light":return 0xfff7f8f4;case "sepia":return 0xffefe2c6;case "black":return Color.BLACK;
  case "custom":try{return Color.parseColor(prefs(c).getString("color","#233b50"));}catch(Exception ignored){return 0xff233b50;}
  default:return 0xff101914;
 }}
 static boolean light(Context c){int b=background(c);return (299*Color.red(b)+587*Color.green(b)+114*Color.blue(b))/1000>155;}
 static int foreground(Context c){return light(c)?0xff20291f:0xffe9f0e4;}
 static void save(Context c,String scheme,String color,int strength){
  if(!scheme.matches("dark|light|sepia|black|custom")||!color.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("Некорректная тема");
  prefs(c).edit().putString("scheme",scheme).putString("color",color).putInt("strength",Math.max(0,Math.min(50,strength))).apply();
 }
 static File wallpaper(Context c){return new File(c.getFilesDir(),"wallpaper.jpg");}
 static void importWallpaper(Context c,InputStream source)throws Exception{
  File temporary=new File(c.getCacheDir(),"wallpaper-input");Bitmap image=null;
  try{
   try(InputStream in=source;FileOutputStream out=new FileOutputStream(temporary)){
    if(in==null)throw new IOException("Нет доступа к картинке.");byte[] buffer=new byte[65536];int n;long size=0;
    while((n=in.read(buffer))!=-1){size+=n;if(size>20*1024*1024)throw new IOException("Выбери изображение до 20 МБ.");out.write(buffer,0,n);}
   }
   image=OcrEngine.decodeImage(temporary);float scale=Math.min(1,1600f/Math.max(image.getWidth(),image.getHeight()));
   if(scale<1){Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*scale)),Math.max(1,Math.round(image.getHeight()*scale)),true);if(small!=image)image.recycle();image=small;}
   File target=new File(c.getFilesDir(),"wallpaper.tmp");try(FileOutputStream out=new FileOutputStream(target)){if(!image.compress(Bitmap.CompressFormat.JPEG,82,out))throw new IOException("Не удалось сохранить фон.");out.getFD().sync();}
   if(!target.renameTo(wallpaper(c)))throw new IOException("Не удалось сохранить фон.");
  }finally{temporary.delete();if(image!=null)image.recycle();}
 }
 static JSONObject payload(Context c)throws Exception{
  JSONObject out=new JSONObject();out.put("scheme",scheme(c));out.put("color",prefs(c).getString("color","#233b50"));out.put("strength",prefs(c).getInt("strength",25));
  File file=wallpaper(c);if(file.exists()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)bytes.write(buf,0,n);}out.put("image","data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP));}
  else out.put("image","");return out;
 }
 static void systemBars(Activity activity){int bg=background(activity);activity.getWindow().setStatusBarColor(bg);activity.getWindow().setNavigationBarColor(bg);activity.getWindow().getDecorView().setSystemUiVisibility(light(activity)?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);}
 static void readerBackground(Activity activity,View root){
  int bg=background(activity);File file=wallpaper(activity);Bitmap bitmap=file.exists()?BitmapFactory.decodeFile(file.toString()):null;
  if(bitmap==null)root.setBackgroundColor(bg);else{
   BitmapDrawable picture=new BitmapDrawable(activity.getResources(),bitmap);picture.setGravity(android.view.Gravity.FILL);
   int alpha=255*(100-prefs(activity).getInt("strength",25))/100;
   root.setBackground(new LayerDrawable(new Drawable[]{picture,new ColorDrawable((bg&0x00ffffff)|(alpha<<24))}));
  }
  systemBars(activity);
 }
}
