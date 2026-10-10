#!/usr/bin/env python3
"""Regression tests for raw IME insets and the actual clipboard window policy."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
work = root / '.test-build' / 'position'
jdk = Path(os.environ.get('CLIPBOARD_JDK', str(Path.home() / 'Library/Java/JavaVirtualMachines/temurin-17.0.18/Contents/Home')))
sources = {
 'android/content/Context.java': 'package android.content; public class Context {}',
 'android/animation/Animator.java': 'package android.animation; public class Animator { public boolean isRunning(){return false;} public void cancel(){} }',
 'android/graphics/Rect.java': 'package android.graphics; public class Rect { public String toShortString(){return "";} }',
 'android/graphics/Insets.java': '''package android.graphics;
public class Insets {
 public static final Insets NONE=new Insets(0,0,0,0);
 public final int left,top,right,bottom;
 public Insets(int bottom){this(0,0,0,bottom);}
 public Insets(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
}''',
 'android/view/DisplayCutout.java': '''package android.view;
import android.graphics.Insets;
public class DisplayCutout {
 public int safeTop,safeBottom; public Insets waterfall=Insets.NONE;
 public Insets getWaterfallInsets(){return waterfall;}
 public int getSafeInsetTop(){return safeTop;}
 public int getSafeInsetBottom(){return safeBottom;}
}''',
 'android/view/WindowInsets.java': '''package android.view;
import android.graphics.Insets;
public class WindowInsets {
 public int ime,nav,stableNav; public boolean imeVisible;public DisplayCutout cutout;
 public WindowInsets(int ime,int nav,int stableNav){this.ime=ime;this.nav=nav;this.stableNav=stableNav;imeVisible=ime>0;}
 public Insets getInsets(int type){return new Insets(type==1?ime:nav);}
 public boolean isVisible(int type){return type==1&&imeVisible;}
 public DisplayCutout getDisplayCutout(){return cutout;}
 public Insets getInsetsIgnoringVisibility(int type){if(type!=2)throw new IllegalArgumentException();return new Insets(stableNav);}
 public static class Type{public static int ime(){return 1;}public static int navigationBars(){return 2;}}
 public static class Builder {
  private final WindowInsets value;
  public Builder(WindowInsets current){value=new WindowInsets(current.ime,current.nav,current.stableNav);value.cutout=current.cutout;}
  public Builder setInsets(int type,Insets insets){if(type==1)value.ime=insets.bottom;else value.nav=insets.bottom;return this;}
  public Builder setVisible(int type,boolean visible){if(type==1)value.imeVisible=visible;return this;}
  public WindowInsets build(){return value;}
 }
}''',
 'android/view/View.java': '''package android.view;
import android.graphics.Rect;
public class View {
 private ViewGroup.LayoutParams params;
 public int layoutRequests; public float translationX,translationY;
 public ViewGroup.LayoutParams getLayoutParams(){return params;}
 public void setLayoutParams(ViewGroup.LayoutParams value){params=value;layoutRequests++;}
 public boolean isAttachedToWindow(){return true;}
 public boolean postDelayed(Runnable task,long delay){return true;}
 public boolean getGlobalVisibleRect(Rect rect){return true;}
 public void getLocationOnScreen(int[] location){}
 public int getWidth(){return 1216;}public int getHeight(){return 2640;}
}''',
 'android/view/ViewGroup.java': '''package android.view;
public class ViewGroup extends View {
 public static class LayoutParams {}
 public static class MarginLayoutParams extends LayoutParams {
  public int leftMargin,topMargin,rightMargin,bottomMargin;
  public void setMargins(int l,int t,int r,int b){leftMargin=l;topMargin=t;rightMargin=r;bottomMargin=b;}
 }
}''',
 'android/view/Window.java': '''package android.view;
public class Window { public void setSoftInputMode(int mode){}public View peekDecorView(){return null;} }''',
 'android/view/WindowMetrics.java': '''package android.view;
public class WindowMetrics {public WindowInsets getWindowInsets(){return new WindowInsets(0,24,24);} }''',
 'android/view/WindowManager.java': '''package android.view;
public interface WindowManager {
 void updateViewLayout(View view,LayoutParams params); WindowMetrics getCurrentWindowMetrics();
 public static class LayoutParams {
  public static final int SOFT_INPUT_MASK_ADJUST=0xf0,SOFT_INPUT_ADJUST_NOTHING=0x30;
  public static final int FLAG_NOT_FOCUSABLE=8,FLAG_ALT_FOCUSABLE_IM=0x20000;
  public int softInputMode,flags,fit=255;
  public void setFitInsetsTypes(int value){fit=value;} public int getFitInsetsTypes(){return fit;}
 }
}''',
 'dev/thom/clipboardglass/PreviewPositionTest.java': '''package dev.thom.clipboardglass;
import android.view.*;
import android.graphics.Insets;
public class PreviewPositionTest {
 public static void main(String[] args) {
  PreviewPosition position=new PreviewPosition(); View view=new View();
  ViewGroup.MarginLayoutParams margins=new ViewGroup.MarginLayoutParams();
  // The first native call can contain the full IME height before any copy hook runs.
  margins.setMargins(8,20,8,1200);view.setLayoutParams(margins);
  for(int keyboard:new int[]{1200,900,400,0,900,0}) {
   WindowInsets raw=new WindowInsets(keyboard,keyboard>0?0:24,24);
   check(position.apply(view,raw,1));
   check(margins.leftMargin==0 && margins.bottomMargin==24);
   WindowInsets fixed=KeyboardPreview.screenInsets(raw);
   check(fixed.ime==0 && !fixed.imeVisible && fixed.nav==24);
   check(raw.ime==keyboard && raw.imeVisible==(keyboard>0));
  }
  int requests=view.layoutRequests;
  for(int i=0;i<100;i++) position.apply(view,new WindowInsets(900,0,24),1);
  check(view.layoutRequests==requests);
  // Correct a stale keyboard-shifted margin instead of capturing it.
  margins.bottomMargin=900;view.translationX=12;view.translationY=-18;
  position.apply(view,new WindowInsets(900,0,24),1);
  check(margins.bottomMargin==24 && view.translationX==12 && view.translationY==-18);
  DisplayCutout cutout=new DisplayCutout();cutout.safeTop=80;cutout.safeBottom=40;
  cutout.waterfall=new Insets(3,6,4,5);
  WindowInsets raw=new WindowInsets(900,0,24);raw.cutout=cutout;
  position.apply(view,raw,1);
  check(margins.leftMargin==3 && margins.topMargin==80 && margins.rightMargin==4 && margins.bottomMargin==40);
  position.apply(view,raw,2);check(margins.topMargin==6 && margins.bottomMargin==24);
  View unsupported=new View();unsupported.setLayoutParams(new ViewGroup.LayoutParams());
  check(!position.apply(unsupported,raw,1));
  for(int adjust:new int[]{0,0x10,0x20,0x30}) {
   WindowManager.LayoutParams params=new WindowManager.LayoutParams();
   params.softInputMode=adjust|0x105;params.flags=0xe0528;
   int unrelated=params.flags & ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
   PreviewPosition.pinWindow(params);
   check(params.softInputMode==0x135);
   check(params.flags==unrelated && (params.flags&8)!=0);
   check(params.fit==0);
  }
  System.out.println("Position: raw first-call IME, open/close cycles, stale margins, cutout, drag independence, window pan and layering passed");
 }
 private static void check(boolean ok){if(!ok)throw new AssertionError();}
}'''
}
for name,source in sources.items():
    path=work/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(source)
classes=work/'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(jdk/'bin/javac'),'--release','17','-d',str(classes),
 *[str(root/'src/dev/thom/clipboardglass'/name) for name in
   ['PreviewPosition.java','KeyboardPreview.java','Reflect.java']],
 *[str(work/name) for name in sources]],check=True)
subprocess.run([str(jdk/'bin/java'),'-cp',str(classes),
 'dev.thom.clipboardglass.PreviewPositionTest'],check=True)
