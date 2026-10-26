package net.dalerank.caesaria;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Telemetry (spec section 7): captures logcat of the previous session and
 * native/java crashes, then uploads them to a dedicated GitHub repository
 * through the Issues API so the maintainer can read device failures without
 * any user interaction.
 *
 * Credentials come from (in priority order):
 *   1. environment CAESARIA_TELEMETRY_TOKEN / CAESARIA_TELEMETRY_REPO (CI/dev)
 *   2. assets/telemetry.properties (written by GitHub Actions from repo secrets;
 *      the token is never committed to the public repository)
 *
 * When no credentials are available the reports stay on the device.
 */
public class CrashReporter
{
  private static final String TAG = "CAESARIA_TELEMETRY";
  private static final String DIR = "telemetry";

  private static String sToken = "";
  private static String sRepo = "";
  private static String sApkVersion = "";
  private static String sCommit = "";
  private static String sSessionLogPath = "";
  private static Thread.UncaughtExceptionHandler sPrevHandler;

  public static void init( Context ctx )
  {
    try
    {
      File dir = new File( ctx.getExternalFilesDir( null ), DIR );
      if( !dir.exists() ) dir.mkdirs();

      loadConfig( ctx );
      sApkVersion = appVersion( ctx );
      sCommit = readCommit( ctx );

      capturePreviousSession( ctx );

      sPrevHandler = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler( new Thread.UncaughtExceptionHandler()
      {
        @Override
        public void uncaughtException( Thread t, Throwable e )
        {
          try
          {
            saveCrash( ctx, e );
            uploadCrash( ctx );
          }
          catch( Throwable ignored ) {}
          if( sPrevHandler != null )
            sPrevHandler.uncaughtException( t, e );
        }
      } );

      Log.i( TAG, "initialized (repo=" + (sRepo.isEmpty() ? "none" : sRepo) + ")" );
    }
    catch( Throwable th )
    {
      Log.w( TAG, "init failed", th );
    }
  }

  private static void loadConfig( Context ctx )
  {
    // 1) environment (only useful under CI/dev shells)
    sToken = envOrEmpty( "CAESARIA_TELEMETRY_TOKEN" );
    sRepo = envOrEmpty( "CAESARIA_TELEMETRY_REPO" );

    // 2) assets/telemetry.properties injected by the build pipeline
    if( sToken.isEmpty() )
    {
      try
      {
        InputStream in = ctx.getAssets().open( "telemetry.properties" );
        java.util.Properties p = new java.util.Properties();
        p.load( in );
        in.close();
        sToken = p.getProperty( "telemetry.token", "" ).trim();
        sRepo = p.getProperty( "telemetry.repo", "" ).trim();
      }
      catch( Throwable ignored ) {}
    }
  }

  private static String envOrEmpty( String name )
  {
    try { String v = System.getenv( name ); return v == null ? "" : v.trim(); }
    catch( Throwable t ) { return ""; }
  }

  private static String appVersion( Context ctx )
  {
    try
    {
      return ctx.getPackageManager().getPackageInfo( ctx.getPackageName(), 0 ).versionName;
    }
    catch( PackageManager.NameNotFoundException e ) { return "?"; }
  }

  private static String readCommit( Context ctx )
  {
    try
    {
      InputStream in = ctx.getAssets().open( "build-commit.txt" );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      return bo.toString( "UTF-8" ).trim();
    }
    catch( Throwable t ) { return "unknown"; }
  }

  /** dump the current logcat ring buffer (previous session if app just started) */
  private static void capturePreviousSession( Context ctx )
  {
    try
    {
      Process p = Runtime.getRuntime().exec( new String[]{ "logcat", "-d", "-t", "3000", "-v", "time" } );
      InputStream in = p.getInputStream();
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();

      File f = new File( logRoot( ctx ), "previous_session.log" );
      java.io.FileOutputStream fo = new java.io.FileOutputStream( f );
      fo.write( bo.toByteArray() );
      fo.close();
      sSessionLogPath = f.getAbsolutePath();
      Log.i( TAG, "previous session log saved: " + f.getAbsolutePath() );
    }
    catch( Throwable t )
    {
      Log.w( TAG, "logcat capture failed", t );
    }
  }

  private static File logRoot( Context ctx )
  {
    return new File( ctx.getExternalFilesDir( null ), DIR );
  }

  private static void saveCrash( Context ctx, Throwable e )
  {
    try
    {
      String ts = new SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.US ).format( new Date() );
      File f = new File( new File( ctx.getExternalFilesDir( null ), DIR ), "crash-" + ts + ".txt" );
      StringBuilder sb = new StringBuilder();
      sb.append( "CaesarIA crash report\n" )
        .append( "version: " ).append( sApkVersion ).append( '\n' )
        .append( "commit: " ).append( sCommit ).append( '\n' )
        .append( "device: " ).append( Build.MANUFACTURER ).append( ' ' ).append( Build.MODEL ).append( '\n' )
        .append( "android: " ).append( Build.VERSION.RELEASE ).append( " (api " ).append( Build.VERSION.SDK_INT ).append( ")\n" )
        .append( "phase: see CAESARIA_LAUNCHER markers in attached log\n" )
        .append( "thread: " ).append( t_name( e ) ).append( "\n\n" )
        .append( Log.getStackTraceString( e ) ).append( '\n' );

      // include the captured logcat for context
      if( !sSessionLogPath.isEmpty() )
      {
        try
        {
          byte[] log = readFile( new File( sSessionLogPath ) );
          sb.append( "\n----- logcat -----\n" ).append( new String( log, StandardCharsets.UTF_8 ) );
        }
        catch( Throwable ignored ) {}
      }

      java.io.FileOutputStream fo = new java.io.FileOutputStream( f );
      fo.write( sb.toString().getBytes( StandardCharsets.UTF_8 ) );
      fo.close();
      Log.e( TAG, "crash saved: " + f.getAbsolutePath() );
    }
    catch( Throwable t ) { Log.w( TAG, "saveCrash failed", t ); }
  }

  private static String t_name( Throwable e )
  {
    StackTraceElement[] st = e.getStackTrace();
    return st.length > 0 ? st[0].toString() : "?";
  }

  private static byte[] readFile( File f )
  {
    try
    {
      FileInputStream in = new FileInputStream( f );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      return bo.toByteArray();
    }
    catch( Throwable t ) { return new byte[0]; }
  }

  private static void uploadCrash( final Context ctx )
  {
    if( sToken.isEmpty() || sRepo.isEmpty() )
    {
      Log.w( TAG, "no telemetry credentials: crash kept on device only" );
      return;
    }

    new Thread( new Runnable()
    {
      @Override
      public void run()
      {
        File dir = new File( ctx.getExternalFilesDir( null ), DIR );
        File[] files = dir.listFiles();
        if( files == null ) return;
        for( File f : files )
        {
          if( !f.getName().startsWith( "crash-" ) ) continue;
          String body = new String( readFile( f ), StandardCharsets.UTF_8 );
          if( body.length() > 60000 ) body = body.substring( 0, 60000 ) + "\n... (truncated)";
          String title = "[auto] crash on " + Build.MODEL + " " + sApkVersion;
          boolean ok = postIssue( title, body );
          Log.i( TAG, "upload " + f.getName() + ": " + (ok ? "ok" : "failed") );
          if( ok ) f.delete();
        }
      }
    }, "caesaria-telemetry" ).start();
  }

  private static boolean postIssue( String title, String body )
  {
    try
    {
      URL url = new URL( "https://api.github.com/repos/" + sRepo + "/issues" );
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod( "POST" );
      conn.setDoOutput( true );
      conn.setConnectTimeout( 15000 );
      conn.setReadTimeout( 30000 );
      conn.setRequestProperty( "Authorization", "token " + sToken );
      conn.setRequestProperty( "Accept", "application/vnd.github+json" );
      conn.setRequestProperty( "Content-Type", "application/json" );

      String json = "{\"title\":\"" + jesc( title ) + "\",\"body\":\"" + jesc( body ) + "\",\"labels\":[\"telemetry\"]}";
      OutputStream os = conn.getOutputStream();
      os.write( json.getBytes( StandardCharsets.UTF_8 ) );
      os.close();

      int code = conn.getResponseCode();
      InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
      if( in != null ) in.close();
      conn.disconnect();
      return code / 100 == 2;
    }
    catch( Throwable t )
    {
      Log.w( TAG, "postIssue failed", t );
      return false;
    }
  }

  private static String jesc( String s )
  {
    return s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" )
            .replace( "\r", "\\r" ).replace( "\n", "\\n" )
            .replace( "\t", "\\t" );
  }
}
