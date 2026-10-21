package ru.ustavy.offline;
import android.content.Context;
import android.graphics.*;
import android.view.*;

public final class ZoomPageView extends View {
 private Bitmap bitmap;private float zoom=1,x=0,y=0,lastX,lastY;
 private final ScaleGestureDetector pinch;private final GestureDetector gestures;
 public ZoomPageView(Context context){
  super(context);setContentDescription("Страница книги. Масштаб меняется двумя пальцами или двойным нажатием.");
  pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
   @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(5,zoom*d.getScaleFactor()));constrain();invalidate();return true;}
  });
  gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
   @Override public boolean onDown(MotionEvent e){return true;}
   @Override public boolean onDoubleTap(MotionEvent e){zoom=zoom>1?1:2.5f;x=y=0;invalidate();return true;}
  });
 }
 public void setBitmap(Bitmap b){bitmap=b;zoom=1;x=y=0;invalidate();}
 public Bitmap getBitmap(){return bitmap;}
 public void changeZoom(float factor){zoom=Math.max(1,Math.min(5,zoom*factor));constrain();invalidate();}
 private float fit(){return bitmap==null?1:Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());}
 private void constrain(){if(bitmap==null)return;float s=fit()*zoom;float maxX=Math.max(0,(bitmap.getWidth()*s-getWidth())/2),maxY=Math.max(0,(bitmap.getHeight()*s-getHeight())/2);x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(bitmap==null)return;float s=fit()*zoom;canvas.save();canvas.translate((getWidth()-bitmap.getWidth()*s)/2+x,(getHeight()-bitmap.getHeight()*s)/2+y);canvas.scale(s,s);canvas.drawBitmap(bitmap,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));canvas.restore();}
 @Override public boolean onTouchEvent(MotionEvent e){
  pinch.onTouchEvent(e);gestures.onTouchEvent(e);
  if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}
  else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(e.getPointerCount()==1&&!pinch.isInProgress()){x+=e.getX()-lastX;y+=e.getY()-lastY;constrain();invalidate();}lastX=e.getX();lastY=e.getY();}
  else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP){lastX=e.getX(0);lastY=e.getY(0);}
  if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
  return true;
 }
 @Override public boolean performClick(){super.performClick();return true;}
}

