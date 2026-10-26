package net.dalerank.caesaria;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SDL game activity. Only opened by LauncherActivity with Intent extras:
 *   action    = "new" | "continue" | "loadfile" | "options" | absent (engine menu)
 *   loadfile  = absolute path of a save file (used with action=loadfile/continue)
 *
 * The extras are forwarded to the native main() as command line arguments
 * (-action <value> -loadfile <path>) through SDLActivity.getArguments().
 */
public class CaesarIA extends SDLActivity
{
  private static final String TAG = "CAESARIA_LAUNCHER";

  // bump when bundled assets change so resources are re-extracted
  private static final int ASSETS_REV = 1;

  @Override
  protected String[] getLibraries()
  {
    // everything (SDL2, mixer, ttf, engine deps) is statically linked into libmain.so
    return new String[]{ "main" };
  }

  @Override
  protected String[] getArguments()
  {
    Intent it = getIntent();
    String action = it != null ? it.getStringExtra( "action" ) : null;
    String loadfile = it != null ? it.getStringExtra( "loadfile" ) : null;

    java.util.ArrayList<String> args = new java.util.ArrayList<String>();
    if( action != null && action.length() > 0 )
    {
      args.add( "-action" );
      args.add( action );
    }
    if( loadfile != null && loadfile.length() > 0 )
    {
      if( action == null || action.length() == 0 )
      {
        args.add( "-action" );
        args.add( "loadfile" );
      }
      args.add( "-loadfile" );
      args.add( loadfile );
    }
    Log.i( TAG, "native arguments: " + args );
    return args.toArray( new String[ args.size() ] );
  }

  @Override
  protected void onCreate( Bundle savedInstanceState )
  {
    CrashReporter.init( this );
    extractEngineResources();
    Log.i( TAG, "SDL activity starting (phase: engine)" );
    super.onCreate( savedInstanceState );
  }

  /** Copy the bundled bin/resources from APK assets into the external files dir. */
  private void extractEngineResources()
  {
    File base = getExternalFilesDir( null );
    if( base == null )
    {
      Log.e( TAG, "external files dir unavailable" );
      return;
    }

    File revMarker = new File( base, ".caesaria_assets_rev" );
    if( revMarker.exists() )
    {
      try
      {
        if( Integer.parseInt( new String( readFile( revMarker ) ).trim() ) >= ASSETS_REV )
          return; // up to date
      }
      catch( Exception ignored ) {}
    }

    Log.i( TAG, "extracting engine resources (rev " + ASSETS_REV + ")" );
    try
    {
      copyAssetsRecursive( "resources", base );
      FileOutputStream fos = new FileOutputStream( revMarker );
      fos.write( String.valueOf( ASSETS_REV ).getBytes() );
      fos.close();
      Log.i( TAG, "engine resources extracted to " + base.getAbsolutePath() );
    }
    catch( IOException e )
    {
      Log.e( TAG, "resource extraction failed", e );
    }
  }

  private static byte[] readFile( File f ) throws IOException
  {
    java.io.FileInputStream in = new java.io.FileInputStream( f );
    byte[] buf = new byte[ (int) f.length() ];
    int off = 0;
    while( off < buf.length )
    {
      int r = in.read( buf, off, buf.length - off );
      if( r < 0 ) break;
      off += r;
    }
    in.close();
    return buf;
  }

  private void copyAssetsRecursive( String assetPath, File outDir ) throws IOException
  {
    String[] list = getAssets().list( assetPath );
    if( list == null || list.length == 0 )
      return; // file, not directory (resources are mostly archives/models)

    File dst = new File( outDir, assetPath );
    boolean ok = dst.isDirectory() || dst.mkdirs();
    if( !ok && !dst.exists() )
      throw new IOException( "cannot create " + dst.getAbsolutePath() );

    for( String name : list )
    {
      String child = assetPath + "/" + name;
      String[] sub = getAssets().list( child );
      if( sub != null && sub.length > 0 )
      {
        copyAssetsRecursive( child, outDir );
      }
      else
      {
        File outFile = new File( outDir, child );
        File parent = outFile.getParentFile();
        if( parent != null && !parent.exists() ) parent.mkdirs();
        InputStream in = getAssets().open( child );
        OutputStream out = new FileOutputStream( outFile );
        byte[] buf = new byte[ 65536 ];
        int r;
        while( ( r = in.read( buf ) ) > 0 ) out.write( buf, 0, r );
        out.close();
        in.close();
      }
    }
  }
}
