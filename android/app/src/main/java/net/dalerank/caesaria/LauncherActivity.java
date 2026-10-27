package net.dalerank.caesaria;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native launcher (spec section 12): splash screen with inicio.jpg and the
 * main menu. The SDL game activity (CaesarIA) is only started from here with
 * explicit Intent extras, so the engine menu is skipped when the user picks a
 * direct action.
 */
public class LauncherActivity extends Activity
{
  private static final String TAG = "CAESARIA_LAUNCHER";

  // roman palette from the mockup
  private static final int COL_BTN_BG = 0xD028160C; // #28160C @ ~82%
  private static final int COL_BTN_BORDER = 0xFFB98A3E; // golden border
  private static final int COL_BTN_TEXT = 0xFFF6DFA4; // light golden text
  private static final int COL_CONTINUE_TEXT = 0xFFE8B29A; // roman red readable variant

  private static final int REQ_PICK_DATA = 41;

  private FrameLayout root;
  private ImageView bgImage;
  private Bitmap splashBitmap;
  private TextView tapText;
  private LinearLayout titleBlock;
  private LinearLayout menuLayer;

  private Button btnContinue, btnNew, btnLoad, btnOptions, btnExit, btnPickData;
  private TextView dataWarn;

  private File baseDir;
  private File resourcesDir;
  private File savesDir;
  private boolean resourcesReady = false;
  private boolean inMenu = false;
  private boolean launching = false;

  private Typeface cinzel;

  @Override
  protected void onCreate( Bundle savedInstanceState )
  {
    super.onCreate( savedInstanceState );
    requestWindowFeature( Window.FEATURE_NO_TITLE );

    // telemetry v2: captures the previous session, generates a report when the
    // last engine run died abnormally and uploads every pending report
    CrashReporter.init( this );

    baseDir = getExternalFilesDir( null );
    resourcesDir = new File( baseDir, "resources" );
    savesDir = new File( baseDir, "saves" );

    Log.i( TAG, "launcher created; data dir = " + baseDir.getAbsolutePath() );

    buildUi();
    applyImmersive();

    // prepare bundled resources in background, then enable the menu actions
    setStateSplash();
    new Thread( new Runnable()
    {
      @Override
      public void run()
      {
        boolean ok = extractIfNeeded();
        runOnUiThread( new Runnable()
        {
          @Override
          public void run() { onResourcesReady( ok ); }
        } );
      }
    }, "caesaria-prepare" ).start();
  }

  // ------------------------------------------------------------------ UI

  private void buildUi()
  {
    root = new FrameLayout( this );
    setContentView( root );

    cinzel = Typeface.createFromAsset( getAssets(), "fonts/Cinzel.ttf" );

    // ---- background image (state 1 & 2), decoded to screen size
    bgImage = new ImageView( this );
    bgImage.setScaleType( ImageView.ScaleType.CENTER_CROP );
    splashBitmap = decodeSplash();
    bgImage.setImageBitmap( splashBitmap );
    root.addView( bgImage, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT ) );

    // ---- soft dark top gradient (spec 12.2)
    View topGrad = new View( this );
    GradientDrawable tg = new GradientDrawable( GradientDrawable.Orientation.TOP_BOTTOM,
        new int[]{ 0xB3000000, 0x00000000 } );
    topGrad.setBackgroundDrawable( tg );
    FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT );
    tp.height = (int) ( getScreenH() * 0.28f );
    root.addView( topGrad, tp );

    // ---- title block (7% from top, centered)
    titleBlock = new LinearLayout( this );
    titleBlock.setOrientation( LinearLayout.VERTICAL );
    titleBlock.setGravity( Gravity.CENTER_HORIZONTAL );

    TextView title = new TextView( this );
    title.setText( "CaesarIA" );
    title.setTypeface( cinzel, Typeface.BOLD );
    title.setTextColor( 0xFFF6DFA4 );
    title.setTextSize( 34 );
    title.setShadowLayer( 6, 0, 2, 0xCC000000 );

    TextView motto = new TextView( this );
    motto.setText( "CONSTRUYE ROMA" );
    motto.setTypeface( cinzel, Typeface.NORMAL );
    motto.setTextColor( 0xFFD9B36C );
    motto.setTextSize( 13 );
    motto.setLetterSpacing( 0.35f );
    motto.setShadowLayer( 4, 0, 1, 0xCC000000 );

    titleBlock.addView( title );
    titleBlock.addView( motto );

    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT );
    tlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    tlp.topMargin = (int) ( getScreenH() * 0.07f );
    root.addView( titleBlock, tlp );

    // ---- blinking "toca para empezar" (6% from bottom)
    tapText = new TextView( this );
    tapText.setText( "TOCA PARA EMPEZAR" );
    tapText.setTypeface( cinzel );
    tapText.setTextColor( 0xFFF6DFA4 );
    tapText.setTextSize( 15 );
    tapText.setLetterSpacing( 0.25f );
    tapText.setShadowLayer( 4, 0, 1, 0xCC000000 );
    AlphaAnimation blink = new AlphaAnimation( 1f, 0.15f );
    blink.setDuration( 900 );
    blink.setRepeatMode( Animation.REVERSE );
    blink.setRepeatCount( Animation.INFINITE );
    tapText.startAnimation( blink );
    FrameLayout.LayoutParams tap = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT );
    tap.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    tap.bottomMargin = (int) ( getScreenH() * 0.06f );
    root.addView( tapText, tap );

    // ---- menu gradient layer (state 2): dark from left -> transparent at 42%
    menuLayer = new LinearLayout( this );
    menuLayer.setOrientation( LinearLayout.VERTICAL );
    menuLayer.setVisibility( View.GONE );
    View grad = new View( this );
    GradientDrawable g = new GradientDrawable( GradientDrawable.Orientation.LEFT_RIGHT,
        new int[]{ COL_BTN_BG, 0x0028160C } );
    g.setGradientCenter( 0f, 0f );
    grad.setBackgroundDrawable( g );
    menuLayer.addView( grad, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f ) );
    // place the gradient across the whole screen; the visual stops at ~42% via shader colors
    root.addView( menuLayer, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT ) );

    buildMenuColumn();

    // ---- any touch on state 1 goes to the menu
    root.setOnClickListener( new View.OnClickListener()
    {
      @Override
      public void onClick( View v )
      {
        if( !inMenu ) showMenu();
      }
    } );
  }

  private void buildMenuColumn()
  {
    LinearLayout col = new LinearLayout( this );
    col.setOrientation( LinearLayout.VERTICAL );

    int screenW = getScreenW();
    int screenH = getScreenH();
    int colW = (int) ( screenW * 0.20f );
    int left = (int) ( screenW * 0.04f );
    int top = (int) ( screenH * 0.30f );
    int gap = (int) ( screenW * 0.014f );
    int minH = (int) ( 48 * getResources().getDisplayMetrics().density );

    btnContinue = menuButton( "Continuar", true );
    btnNew = menuButton( "Nueva partida", false );
    btnLoad = menuButton( "Cargar partida", false );
    btnOptions = menuButton( "Opciones", false );
    btnExit = menuButton( "Salir", false );

    btnContinue.setTextColor( COL_CONTINUE_TEXT );
    btnContinue.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v ) { launchGame( "continue", null ); }
    } );
    btnNew.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v ) { launchGame( "new", null ); }
    } );
    btnLoad.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v ) { showSavePicker(); }
    } );
    btnOptions.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v ) { launchGame( "options", null ); }
    } );
    btnExit.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v )
      {
        Log.i( TAG, "action: exit" );
        finishAffinity();
      }
    } );

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams( colW, minH );
    lp.topMargin = gap;
    col.addView( btnContinue, lp );
    col.addView( btnNew, lp );
    col.addView( btnLoad, lp );
    col.addView( btnOptions, lp );
    col.addView( btnExit, lp );

    // data-folder picker (only visible when resources are missing, spec 12.3)
    btnPickData = menuButton( "Seleccionar carpeta de datos", true );
    btnPickData.setTextSize( 11 );
    btnPickData.setOnClickListener( new View.OnClickListener()
    {
      @Override public void onClick( View v ) { pickDataFolder(); }
    } );
    btnPickData.setVisibility( View.GONE );
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams( (int) ( screenW * 0.34f ), minH );
    plp.topMargin = gap * 2;
    col.addView( btnPickData, plp );

    dataWarn = new TextView( this );
    dataWarn.setTypeface( cinzel );
    dataWarn.setTextColor( 0xFFFFD9B0 );
    dataWarn.setTextSize( 11 );
    dataWarn.setVisibility( View.GONE );
    LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams( (int) ( screenW * 0.36f ), ViewGroup.LayoutParams.WRAP_CONTENT );
    wlp.topMargin = gap;
    col.addView( dataWarn, wlp );

    FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT );
    clp.leftMargin = left;
    clp.topMargin = top;
    menuLayer.addView( col, clp );
  }

  private Button menuButton( String text, boolean small )
  {
    Button b = new Button( this );
    b.setText( text );
    b.setAllCaps( false );
    b.setTypeface( cinzel, Typeface.BOLD );
    b.setTextColor( COL_BTN_TEXT );
    b.setTextSize( small ? 12 : 15 );
    b.setGravity( Gravity.CENTER );
    b.setSingleLine( true );
    b.setTransformationMethod( null ); // keep our own case

    GradientDrawable bg = new GradientDrawable();
    bg.setColor( COL_BTN_BG );
    bg.setCornerRadius( 6 );
    bg.setStroke( 2, COL_BTN_BORDER );
    b.setBackgroundDrawable( bg );

    b.setPadding( 12, 4, 12, 4 );
    return b;
  }

  private void setStateSplash()
  {
    inMenu = false;
    menuLayer.setVisibility( View.GONE );
    tapText.setVisibility( View.VISIBLE );
    Log.i( TAG, "state: splash" );
  }

  private void showMenu()
  {
    inMenu = true;
    Log.i( TAG, "state: menu" );
    tapText.clearAnimation();
    tapText.setVisibility( View.GONE );

    menuLayer.setVisibility( View.VISIBLE );
    menuLayer.setAlpha( 0f );
    menuLayer.animate().alpha( 1f ).setDuration( 800 ).start(); // fade 0.8s

    refreshButtons();
  }

  private void onResourcesReady( boolean ok )
  {
    resourcesReady = ok;
    Log.i( TAG, "resources ready: " + ok + " (" + resourcesDir.getAbsolutePath() + ")" );
    refreshButtons();
    if( !ok )
    {
      Toast.makeText( this, "Faltan datos del juego", Toast.LENGTH_SHORT ).show();
    }
  }

  private void refreshButtons()
  {
    boolean hasSave = hasSaves();
    btnContinue.setEnabled( resourcesReady && hasSave );
    btnNew.setEnabled( resourcesReady );
    btnLoad.setEnabled( resourcesReady && hasSave );
    btnOptions.setEnabled( true );
    btnExit.setEnabled( true );

    if( !resourcesReady && menuLayer.getVisibility() == View.VISIBLE )
    {
      btnPickData.setVisibility( View.VISIBLE );
      dataWarn.setVisibility( View.VISIBLE );
      dataWarn.setText( "No se encontraron datos del juego en:\n"
          + resourcesDir.getAbsolutePath()
          + "\nPuedes copiarlos con el selector de carpetas." );
    }
    else
    {
      btnPickData.setVisibility( View.GONE );
      dataWarn.setVisibility( View.GONE );
    }
  }

  private boolean hasSaves()
  {
    File[] files = savesDir.listFiles();
    if( files == null ) return false;
    for( File f : files )
      if( f.getName().endsWith( ".oc3save" ) ) return true;
    return false;
  }

  // ------------------------------------------------------------- actions

  private void launchGame( String action, String loadfile )
  {
    if( launching )
    {
      Log.i( TAG, "launch ignored: engine already starting" );
      return;
    }
    launching = true;
    Log.i( TAG, "action: " + action + (loadfile != null ? " file=" + loadfile : "") );

    Intent it = new Intent( this, CaesarIA.class );
    it.putExtra( "action", action );
    if( loadfile != null ) it.putExtra( "loadfile", loadfile );
    it.putExtra( "data_path", baseDir.getAbsolutePath() );
    startActivity( it );

    // free the splash memory (spec 12.6); launcher stays in the back stack so
    // quitting the engine returns here
    if( splashBitmap != null )
    {
      bgImage.setImageBitmap( null );
      splashBitmap.recycle();
      splashBitmap = null;
    }
  }

  private void showSavePicker()
  {
    File[] files = savesDir.listFiles();
    final List<File> saves = new ArrayList<File>();
    if( files != null )
    {
      for( File f : files )
        if( f.getName().endsWith( ".oc3save" ) ) saves.add( f );
    }
    if( saves.isEmpty() )
    {
      Log.i( TAG, "save picker: no saves found" );
      Toast.makeText( this, "No hay partidas guardadas", Toast.LENGTH_SHORT ).show();
      return;
    }

    Collections.sort( saves, new Comparator<File>()
    {
      @Override public int compare( File a, File b )
      {
        return Long.valueOf( b.lastModified() ).compareTo( a.lastModified() );
      }
    } );

    CharSequence[] names = new CharSequence[ saves.size() ];
    SimpleDateFormat df = new SimpleDateFormat( "dd/MM/yyyy HH:mm", Locale.US );
    for( int i = 0; i < saves.size(); i++ )
    {
      String base = saves.get( i ).getName().replaceFirst( "\\.oc3save$", "" );
      names[ i ] = base + "   ·   " + df.format( new Date( saves.get( i ).lastModified() ) );
    }

    Log.i( TAG, "save picker: " + saves.size() + " saves" );
    new AlertDialog.Builder( this )
        .setTitle( "Cargar partida" )
        .setItems( names, new android.content.DialogInterface.OnClickListener()
        {
          @Override
          public void onClick( android.content.DialogInterface dlg, int which )
          {
            launchGame( "loadfile", saves.get( which ).getAbsolutePath() );
          }
        } )
        .setNegativeButton( "Cancelar", null )
        .show();
  }

  // --------------------------------------------------- SAF data folder

  private void pickDataFolder()
  {
    try
    {
      Intent it = new Intent( Intent.ACTION_OPEN_DOCUMENT_TREE );
      it.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION );
      startActivityForResult( it, REQ_PICK_DATA );
    }
    catch( Throwable t )
    {
      Toast.makeText( this, "Selector no disponible: " + t.getMessage(), Toast.LENGTH_LONG ).show();
    }
  }

  @Override
  protected void onActivityResult( int requestCode, int resultCode, Intent data )
  {
    super.onActivityResult( requestCode, resultCode, data );
    if( requestCode != REQ_PICK_DATA || resultCode != RESULT_OK || data == null ) return;

    final Uri tree = data.getData();
    try
    {
      getContentResolver().takePersistableUriPermission( tree,
          Intent.FLAG_GRANT_READ_URI_PERMISSION );
    }
    catch( Throwable ignored ) {}

    Toast.makeText( this, "Copiando datos…", Toast.LENGTH_SHORT ).show();
    new Thread( new Runnable()
    {
      @Override
      public void run()
      {
        boolean ok = copyTree( tree, resourcesDir );
        runOnUiThread( new Runnable()
        {
          @Override
          public void run()
          {
            Toast.makeText( LauncherActivity.this,
                ok ? "Datos copiados" : "No se pudieron copiar los datos",
                Toast.LENGTH_LONG ).show();
            resourcesReady = ok || resourcesReady;
            refreshButtons();
          }
        } );
      }
    }, "caesaria-saf" ).start();
  }

  private boolean copyTree( Uri tree, File dstRoot )
  {
    try
    {
      if( !dstRoot.exists() ) dstRoot.mkdirs();
      copyDocChildren( DocumentsContract.buildChildDocumentsUriUsingTree( tree,
          DocumentsContract.getTreeDocumentId( tree ) ), dstRoot, 0 );
      Log.i( TAG, "SAF copy into " + dstRoot.getAbsolutePath() + " done" );
      return true;
    }
    catch( Throwable t )
    {
      Log.e( TAG, "SAF copy failed", t );
      return false;
    }
  }

  private void copyDocChildren( Uri parentUri, File dst, int depth ) throws IOException
  {
    if( depth > 8 ) return;
    String[] proj = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                      DocumentsContract.Document.COLUMN_MIME_TYPE };
    Cursor c = getContentResolver().query( parentUri, proj, null, null, null );
    if( c == null ) return;
    while( c.moveToNext() )
    {
      String docId = c.getString( 0 );
      String name = c.getString( 1 );
      String mime = c.getString( 2 );
      if( name == null ) continue;

      Uri child = DocumentsContract.buildDocumentUriUsingTree( parentUri, docId );
      File outFile = new File( dst, name );

      if( DocumentsContract.Document.MIME_TYPE_DIR.equals( mime ) )
      {
        if( !outFile.exists() ) outFile.mkdirs();
        copyDocChildren( DocumentsContract.buildChildDocumentsUriUsingTree( parentUri, docId ),
            outFile, depth + 1 );
      }
      else
      {
        InputStream in = null;
        OutputStream out = null;
        try
        {
          in = getContentResolver().openInputStream( child );
          if( in == null ) continue;
          out = new FileOutputStream( outFile );
          byte[] buf = new byte[ 65536 ];
          int r;
          while( ( r = in.read( buf ) ) > 0 ) out.write( buf, 0, r );
        }
        finally
        {
          if( in != null ) in.close();
          if( out != null ) out.close();
        }
      }
    }
    c.close();
  }

  // ------------------------------------------------------------- helpers

  private boolean extractIfNeeded()
  {
    if( !inMenu )
    {
      // same extraction contract as CaesarIA.onCreate; idempotent via rev marker
      File marker = new File( baseDir, ".caesaria_assets_rev" );
      try
      {
        if( marker.exists() && Integer.parseInt( new String( readAll( marker ) ).trim() ) >= 1 )
          return resourcesDir.isDirectory();
      }
      catch( Throwable ignored ) {}

      try
      {
        copyAssetsRecursive( "resources", baseDir );
        FileOutputStream fo = new FileOutputStream( marker );
        fo.write( "1".getBytes() );
        fo.close();
        return resourcesDir.isDirectory();
      }
      catch( IOException e )
      {
        Log.e( TAG, "extraction failed", e );
        return resourcesDir.isDirectory() && resourcesDir.list() != null && resourcesDir.list().length > 0;
      }
    }
    return false;
  }

  private static byte[] readAll( File f ) throws IOException
  {
    java.io.FileInputStream in = new java.io.FileInputStream( f );
    byte[] b = new byte[ (int) f.length() ];
    int off = 0;
    while( off < b.length )
    {
      int r = in.read( b, off, b.length - off );
      if( r < 0 ) break;
      off += r;
    }
    in.close();
    return b;
  }

  private void copyAssetsRecursive( String assetPath, File outDir ) throws IOException
  {
    String[] list = getAssets().list( assetPath );
    if( list == null || list.length == 0 )
    {
      // a plain file
      File outFile = new File( outDir, assetPath );
      File parent = outFile.getParentFile();
      if( parent != null && !parent.exists() ) parent.mkdirs();
      InputStream in = getAssets().open( assetPath );
      OutputStream out = new FileOutputStream( outFile );
      byte[] buf = new byte[ 65536 ];
      int r;
      while( ( r = in.read( buf ) ) > 0 ) out.write( buf, 0, r );
      out.close();
      in.close();
      return;
    }
    for( String name : list )
      copyAssetsRecursive( assetPath + "/" + name, outDir );
  }

  private Bitmap decodeSplash()
  {
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inJustDecodeBounds = true;
    BitmapFactory.decodeResource( getResources(), R.drawable.inicio, opts );

    int targetW = Math.max( getScreenW(), 720 );
    int sample = 1;
    while( opts.outWidth / ( sample * 2 ) >= targetW ) sample *= 2;

    opts = new BitmapFactory.Options();
    opts.inSampleSize = sample;
    opts.inPreferredConfig = Bitmap.Config.RGB_565;
    Bitmap bmp = BitmapFactory.decodeResource( getResources(), R.drawable.inicio, opts );
    Log.i( TAG, "splash decoded " + (bmp != null ? bmp.getWidth() + "x" + bmp.getHeight() : "null") );
    return bmp;
  }

  private int getScreenW()
  {
    return getResources().getDisplayMetrics().widthPixels;
  }

  private int getScreenH()
  {
    return getResources().getDisplayMetrics().heightPixels;
  }

  private void applyImmersive()
  {
    getWindow().setFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE );
    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P )
    {
      WindowManager.LayoutParams lp = getWindow().getAttributes();
      lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      getWindow().setAttributes( lp );
    }
  }

  @Override
  public void onWindowFocusChanged( boolean hasFocus )
  {
    super.onWindowFocusChanged( hasFocus );
    if( hasFocus ) applyImmersive();
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    Log.i( TAG, "launcher resumed" );
    launching = false;
    // user may have created/deleted saves while the engine was running
    if( inMenu ) refreshButtons();
  }
}
