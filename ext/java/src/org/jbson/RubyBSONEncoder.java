// BSONEncoder.java

package org.jbson;

import static org.bson.BSON.*;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

import org.bson.BSONEncoder;

import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.javasupport.JavaUtil;

import org.jruby.*;
import org.jruby.runtime.builtin.IRubyObject;

import org.jruby.parser.ReOptions;

import org.bson.BSONObject;
import org.bson.io.*;
import org.bson.types.*;
import org.bson.BSON;

import org.jruby.exceptions.RaiseException;

import org.jruby.java.addons.ArrayJavaAddons;

/**
 * this is meant to be pooled or cached
 * there is some per instance memory for string conversion, etc...
 */
@SuppressWarnings("unchecked")
public class RubyBSONEncoder extends BSONEncoder {

    static final boolean DEBUG = false;

    private Ruby _runtime;

    private RubyModule _rbclsByteBuffer;
    private RubyModule _rbclsDBRef;
    private RubyModule _rbclsInvalidDocument;
    private RubyModule _rbclsRangeError;

    public RubyBSONEncoder(Ruby runtime){
        _runtime = runtime;
        _rbclsByteBuffer = _runtime.getClassFromPath( "BSON::ByteBuffer" );
        _rbclsDBRef = _runtime.getClassFromPath( "BSON::DBRef" );
        _rbclsInvalidDocument = _runtime.getClassFromPath( "BSON::InvalidDocument" );
        _rbclsRangeError = _runtime.getClassFromPath( "RangeError" );
    }

    // Encode needs to return a ByteBuffer
    public RubyObject encode( Object arg ) {
        //Boolean move_id = (Boolean)((RubyBoolean)should_move_id).toJava(Boolean.class);
        RubyHash o = (RubyHash)arg;
        BasicOutputBuffer buf = new BasicOutputBuffer();
        set( buf );
        putObject( o );
        done();

        // NOTE: not happy with this. Probably need to come
        // up with something more efficient.
        byte[] b = buf.toByteArray();
        RubyArray result = RubyArray.newArray(_runtime, b.length);
        for(int i=0; i<b.length; i++) {
          result.aset(new RubyFixnum(_runtime, (long)i),
              new RubyFixnum(_runtime, (long)b[i] ));
        }

        Object bytebuffer = JavaEmbedUtils.invokeMethod(_runtime, _rbclsByteBuffer, "new",
            new Object[] { result }, Object.class);

        return (RubyObject)bytebuffer;
    }

    public void set( OutputBuffer out ){
        if ( _buf != null )
            throw new IllegalStateException( "in the middle of something" );
        
        _buf = out;
    }
 
    public void done(){
        _buf = null;
    }
   
    /**
     * @return true if object was handled
     */
    protected boolean handleSpecialObjects( String name , RubyObject o ){
        return false;
    }
    
    /** Encodes a <code>BSONObject</code>.
     * This is for the higher level api calls
     * @param o the object to encode
     * @return the number of characters in the encoding
     */
    //public int putObject( BSONObject o ){
    //    return putObject( null , o );
    //}
    public int putObject( RubyObject o ) {
        return putObject( null, o );
    }
    /**
     * this is really for embedded objects
     */
    int putObject( String name , RubyObject o ){
        if ( o == null )
            throw new NullPointerException( "can't save a null object" );

        final int start = _buf.getPosition();

        byte myType = OBJECT;
        if ( o instanceof RubyArray )
            myType = ARRAY;

        if ( handleSpecialObjects( name , o ) )
            return _buf.getPosition() - start;

        if ( name != null ){
            _put( myType , name );
        }

        final int sizePos = _buf.getPosition();
        _buf.writeInt( 0 ); // leaving space for sthis.  set it at the end

        List transientFields = null;
        boolean rewriteID = ( myType == OBJECT && name == null );


        if ( myType == OBJECT ) {
            if ( rewriteID && _rbHashHasKey( (RubyHash)o, "_id" ) )
                _putObjectField( "_id" , _rbHashGet( (RubyHash)o, _runtime.newString("_id") ) );

            {
                RubyObject temp = (RubyObject)_rbHashGet( (RubyHash)o, _runtime.newString("_transientFields") );
                if ( temp instanceof RubyArray )
                    transientFields = (RubyArray)temp;
            }

            // Not sure we should invoke this way. Depends on if we can access the OrderedHash.
            RubyArray keys = (RubyArray)JavaEmbedUtils.invokeMethod( _runtime, o , "keys" , new Object[] {}
                , Object.class);

            for (Iterator<RubyObject> i = keys.iterator(); i.hasNext(); ) {

                 Object hashKey = i.next();
                 String str = "";
                 if( hashKey instanceof String) {
                     str = hashKey.toString();
                 }
                 //RubyObject hashKey = i.next();
                 else if (hashKey instanceof RubyString) {
                     str = ((RubyString)hashKey).asJavaString();
                 }
                 else if (hashKey instanceof RubySymbol) {
                     str = ((RubySymbol)hashKey).asJavaString();
                 }

                 //  Still having trouble with symbols here
                 if ( rewriteID && str.equals( "_id" ) )
                    continue;

                //if ( transientFields != null && transientFields.contains( s ) )
                //    continue;

               // System.out.println("STRING VALUE");
                //System.out.println(str);

                RubyObject val = (RubyObject)_rbHashGet( (RubyHash)o, hashKey );

                //System.out.println(val);
                _putObjectField( str , (Object)val );

            }
          }

        if ( _buf.size() > 4 * 1024 * 1024 ) {
            _rbRaise( (RubyClass)_rbclsInvalidDocument,
              "Document is too large (" + _buf.size() + "). BSON documents are limited to 4MB (" +
               4 * 1024 * 1024 + ").");
        }

        _buf.write( EOO );

        _buf.writeInt( sizePos , _buf.getPosition() - sizePos );
        return _buf.getPosition() - start;
    }

    protected void _putObjectField( String name , Object val ){

        if ( name.equals( "_transientFields" ) )
            return;

        if ( DEBUG ) System.out.println( "\t put thing : " + name );

        if ( name.equals( "$where") && val instanceof String ){
            _put( CODE , name );
            _putValueString( val.toString() );
            return;
        }

        // val = BSON.applyEncodingHooks( val );

        if ( val instanceof RubyNil )
            putNull(name);

        else if ( val instanceof RubyTime ) {
            Date jval = ((RubyTime)val).getJavaDate();
            putDate( name , jval );
        }

        else if ( val instanceof RubyFixnum ) {
            long jval = ((RubyFixnum)val).getLongValue();
            putNumber(name, (Number)jval );
        }

        else if ( val instanceof RubyFloat ) {
            double jval = ((RubyFloat)val).getValue();
            putNumber(name, (Number)jval );
        }

        else if ( val instanceof Number )
           putNumber(name, (Number)val );

        else if ( val instanceof RubyString ) {
            System.out.println("putting string");
            putRubyString(name, val.toString() );
        }

        else if ( val instanceof String )
            putString(name, val.toString() );

        else if ( val instanceof RubyBoolean )
            putBoolean(name, (Boolean)((RubyBoolean)val).toJava(Boolean.class));

        else if ( val instanceof RubyRegexp )
            putRubyRegexp(name, (RubyRegexp)val );

        else if ( val instanceof Map )
            putMap( name , (Map)val );

        else if ( val instanceof Iterable)
            putIterable( name , (Iterable)val );

        else if ( val instanceof byte[] )
            putBinary( name , (byte[])val );

        else if ( val.getClass().isArray() )
            putIterable( name , Arrays.asList( (Object[])val ) );

        else if (val instanceof RubySymbol) {
            putSymbol(name, new Symbol(val.toString()));
        }

        else if (val instanceof RubyBignum) {
            _rbRaise( (RubyClass)_rbclsRangeError , "MongoDB can only handle 8-byte ints" );
        }

        // This is where we handle special types defined in the Ruby BSON.
        else if ( val instanceof RubyObject ) {
          String klass = JavaEmbedUtils.invokeMethod(_runtime, val, "class", new Object[] {}, Object.class).toString();
          if( klass.equals( "BSON::ObjectID" ) ) {
              putRubyObjectId(name, (RubyObject)val );
          }
          else if ( klass.equals( "BSON::Code" ) ) {
              putRubyCodeWScope(name, (RubyObject)val );
          }
          else if ( klass.equals( "BSON::Binary" ) ) {
              putRubyBinary( name , (RubyObject)val );
          }
          else if ( klass.equals("BSON::MinKey") ) {
              _put( MINKEY, name );
          }
          else if ( klass.equals("BSON::MaxKey") ) {
              _put( MAXKEY, name );
          }
          else if ( klass.equals("BSON::DBRef") ) {
              RubyHash ref = (RubyHash)JavaEmbedUtils.invokeMethod(_runtime, val,
                  "to_hash", new Object[] {}, Object.class);
              putMap( name , (Map)ref );
          }
          else if ( klass.equals("Date") || klass.equals("DateTime") || klass.equals("ActiveSupport::TimeWithZone") ) {
              _rbRaise( (RubyClass)_rbclsInvalidDocument,
                  klass + " is not currently supported; use a UTC Time instance instead.");
          }
          else {
              _rbRaise( (RubyClass)_rbclsInvalidDocument,
                "Cannot serialize " + klass + " as a BSON type; it either isn't supported or won't translate to BSON.");

          }
        }
    }

    private void putIterable( String name , Iterable l ){
        _put( ARRAY , name );
        final int sizePos = _buf.getPosition();
        _buf.writeInt( 0 );

        int i=0;
        for ( Object obj: l ) {
            _putObjectField( String.valueOf( i ) , obj );
            i++;
        }


        _buf.write( EOO );
        _buf.writeInt( sizePos , _buf.getPosition() - sizePos );        
    }

    private void putMap( String name , Map m ){
        _put( OBJECT , name );
        final int sizePos = _buf.getPosition();
        _buf.writeInt( 0 );

        for ( Map.Entry entry : (Set<Map.Entry>)m.entrySet() )
            _putObjectField( entry.getKey().toString() , entry.getValue() );

        _buf.write( EOO );
        _buf.writeInt( sizePos , _buf.getPosition() - sizePos );
    }


    protected void putNull( String name ){
        _put( NULL , name );
    }

    protected void putUndefined(String name){
        _put(UNDEFINED, name);
    }

    protected void putTimestamp(String name, BSONTimestamp ts ){
        _put( TIMESTAMP , name );
        _buf.writeInt( ts.getInc() );
        _buf.writeInt( ts.getTime() );
    }

    protected void putRubyCodeWScope( String name , RubyObject code ){
        _put( CODE_W_SCOPE , name );
        int temp = _buf.getPosition();
        _buf.writeInt( 0 );

        _putValueString( code.toString() );

        putObject( (RubyObject)JavaEmbedUtils.invokeMethod(_runtime, code, "scope", new Object[] {}, Object.class) );

        _buf.writeInt( temp , _buf.getPosition() - temp );
    }

    protected void putCodeWScope( String name , CodeWScope code ){
        _put( CODE_W_SCOPE , name );
        int temp = _buf.getPosition();
        _buf.writeInt( 0 );
        _putValueString( code.getCode() );
        //putObject( (Object)code.getScope() , false);
        _buf.writeInt( temp , _buf.getPosition() - temp );
    }

    protected void putBoolean( String name , Boolean b ){
        _put( BOOLEAN , name );
        _buf.write( b ? (byte)0x1 : (byte)0x0 );
    }

    protected void putDate( String name , Date d ){
        _put( DATE , name );
        _buf.writeLong( d.getTime() );
    }

    protected void putNumber( String name , Number n ){
        if ( n instanceof Integer ||
             n instanceof Short ||
             n instanceof Byte ||
             n instanceof AtomicInteger ){
                _put( NUMBER_INT , name );
                _buf.writeInt( n.intValue() );
             }
        else if ( n instanceof Long ||
                  n instanceof AtomicLong ) {
            _put( NUMBER_LONG , name );
            _buf.writeLong( n.longValue() );
        }
        else {
            _put( NUMBER , name );
            _buf.writeDouble( n.doubleValue() );
        }
    }

    private void putRubyBinary( String name , RubyObject binary ) {
        RubyArray rarray = (RubyArray)JavaEmbedUtils.invokeMethod(_runtime, binary, "to_a", new Object[] {}, Object.class);
        Long rbSubtype = (Long)JavaEmbedUtils.invokeMethod(_runtime, binary, "subtype", new Object[] {}, Object.class);
        long subtype = rbSubtype.longValue();
        byte[] data = ra2ba( rarray );
        if ( subtype == 2 ) {
          putBinary( name, data );
        }
        else {
          _put( BINARY , name );
          _buf.writeInt( data.length );
          _buf.write( (byte)subtype );
          _buf.write( data );
        }
    }

    protected void putBinary( String name , byte[] data ){
        _put( BINARY , name );
        _buf.writeInt( 4 + data.length );

        _buf.write( B_BINARY );
        _buf.writeInt( data.length );
        int before = _buf.getPosition();
        _buf.write( data );
        int after = _buf.getPosition();

        com.mongodb.util.MyAsserts.assertEquals( after - before , data.length );
    }

    protected void putBinary( String name , Binary val ){
        _put( BINARY , name );
        _buf.writeInt( val.length() );
        _buf.write( val.getType() );
        _buf.write( val.getData() );
    }

    protected void putUUID( String name , UUID val ){
        _put( BINARY , name );
        _buf.writeInt( 4 + 64*2);
        _buf.write( 3 );// B_UUID );
        _buf.writeLong( val.getMostSignificantBits());
        _buf.writeLong( val.getLeastSignificantBits());
    }

    protected void putSymbol( String name , Symbol s ){
        _putString(name, s.getSymbol(), SYMBOL);
    }

    protected void putRubyString( String name , String s ) {
        _put( STRING , name );
        _putValueString( s );
    }

    protected void putString(String name, String s) {
        _putString(name, s, STRING);
    }

    private void _putString( String name , String s, byte type ){
        _put( type , name );
        _putValueString( s );
    }

    private void putRubyObjectId( String name, RubyObject oid ) {
        _put( OID , name );

        RubyArray roid = (RubyArray)JavaEmbedUtils.invokeMethod(_runtime, oid, "to_a", new Object[] {}, Object.class);
        byte[] joid = ra2ba( (RubyArray)roid );

        _buf.writeInt( convertToInt(joid, 0) );
        _buf.writeInt( convertToInt(joid, 4) );
        _buf.writeInt( convertToInt(joid, 8) );
    }

    private void putRubyRegexp( String name, RubyRegexp r ) {
        _put( REGEX , name );
        _put( (String)((RubyString)r.source()).toJava(String.class) );

        int regexOptions = (int)((RubyFixnum)r.options()).getLongValue();
        String options   = "";

        if( (regexOptions & ReOptions.RE_OPTION_IGNORECASE) != 0 )
          options = options.concat( "i" );

        if( (regexOptions & ReOptions.RE_OPTION_MULTILINE) != 0 )
          options = options.concat( "m" );

        if( (regexOptions & ReOptions.RE_OPTION_EXTENDED) != 0 )
          options = options.concat( "x" );

        _put( options );
    }

    // ---------------------------------------------
    // Ruby-based helper methods.

    // Converts four bytes from a byte array to an int
    private int convertToInt(byte[] b, int offset) {
      int intVal = ((b[offset + 3] & 0xff) << 24) | ((b[offset + 2] & 0xff) << 16) | ((b[offset + 1] & 0xff) << 8) | ((b[offset] & 0xff));

      return intVal;
    }

    // Ruby array to byte array
    private byte[] ra2ba( RubyArray rArray ) {
        int len  = rArray.getLength();
        byte[] b = new byte[len];
        int n    = 0;

        for ( Iterator<Object> i = rArray.iterator(); i.hasNext(); ) {
            Object value = i.next();
            b[n] = (byte)((Long)value).intValue();
            n++;
        }

        return b;
    }

    // Helper method for getting a value from a Ruby hash.
    private IRubyObject _rbHashGet(RubyHash hash, Object key) {
        if (key instanceof String) {
            return hash.op_aref( _runtime.getCurrentContext(), _runtime.newString((String)key) );
        }
        else {
            return hash.op_aref( _runtime.getCurrentContext(), (RubyObject)key );
        }
    }

    // Helper method for checking whether a Ruby hash has a certain key.
    private boolean _rbHashHasKey(RubyHash hash, String key) {
        RubyBoolean b = hash.has_key_p( _runtime.newString( key ) );
        return b == _runtime.getTrue();
    }

    // Helper method for setting a value in a Ruby hash.
    private IRubyObject _rbHashSet(RubyHash hash, String key, IRubyObject value) {
        return hash.op_aset( _runtime.getCurrentContext(), _runtime.newString( key ), value );
    }


    // Helper method for returning all keys from a Ruby hash.
    private RubyArray _rbHashKeys(RubyHash hash) {
        return hash.keys();
    }

    // Helper for raising a Ruby exception and aborting serialization.
    private RaiseException _rbRaise( RubyClass exceptionClass , String message ) {
        done();
        throw new RaiseException( _runtime, exceptionClass, message, true );
    }

    // ----------------------------------------------

    /**
     * Encodes the type and key.
     * 
     */
    protected void _put( byte type , String name ){
        _buf.write( type );
        _put( name );
    }

    protected void _putValueString( String s ){
        int lenPos = _buf.getPosition();
        _buf.writeInt( 0 ); // making space for size
        int strLen = _put( s );
        _buf.writeInt( lenPos , strLen );
    }
    
    void _reset( Buffer b ){
        b.position(0);
        b.limit( b.capacity() );
    }

    /**
     * puts as utf-8 string
     */
    protected int _put( String str ){
        int total = 0;

        final int len = str.length();
        int pos = 0;
        while ( pos < len ){
            _reset( _stringC );
            _reset( _stringB );

            int toEncode = Math.min( _stringC.capacity() - 1, len - pos );
            _stringC.put( str , pos , pos + toEncode );
            _stringC.flip();
            
            CoderResult cr = _encoder.encode( _stringC , _stringB , false );
            
            if ( cr.isMalformed() || cr.isUnmappable() )
                throw new IllegalArgumentException( "malforumed string" );
            
            if ( cr.isOverflow() )
                throw new RuntimeException( "overflor should be impossible" );
            
            if ( cr.isError() )
                throw new RuntimeException( "should never get here" );

            if ( ! cr.isUnderflow() )
                throw new RuntimeException( "this should always be true" );

            total += _stringB.position();
            _buf.write( _stringB.array() , 0 , _stringB.position() );

            pos += toEncode;
        }

        _buf.write( (byte)0 );
        total++;

        return total;
    }

    public void writeInt( int x ){
        _buf.writeInt( x );
    }

    public void writeLong( long x ){
        _buf.writeLong( x );
    }
    
    public void writeCString( String s ){
        _put( s );
    }

    protected OutputBuffer _buf;
    
    private CharBuffer _stringC = CharBuffer.wrap( new char[256 + 1] );
    private ByteBuffer _stringB = ByteBuffer.wrap( new byte[1024 + 1] );
    private CharsetEncoder _encoder = Charset.forName( "UTF-8" ).newEncoder();

}