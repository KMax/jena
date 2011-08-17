/*
 * (c) Copyright 2011 Epimorphics Ltd.
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.tdb.transaction;

import static com.hp.hpl.jena.tdb.sys.SystemTDB.SizeOfInt ;
import static com.hp.hpl.jena.tdb.transaction.JournalEntryType.Block ;

import java.nio.ByteBuffer ;
import java.util.Iterator ;
import java.util.zip.Adler32 ;

import org.openjena.atlas.iterator.IteratorSlotted ;
import org.openjena.atlas.lib.Bytes ;
import org.openjena.atlas.lib.Closeable ;
import org.openjena.atlas.lib.FileOps ;
import org.openjena.atlas.lib.Sync ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;

import com.hp.hpl.jena.tdb.base.block.Block ;
import com.hp.hpl.jena.tdb.base.file.BufferChannel ;
import com.hp.hpl.jena.tdb.base.file.BufferChannelFile ;
import com.hp.hpl.jena.tdb.base.file.BufferChannelMem ;
import com.hp.hpl.jena.tdb.base.file.Location ;
import com.hp.hpl.jena.tdb.sys.FileRef ;
import com.hp.hpl.jena.tdb.sys.Names ;

/** The Journal is slightly odd - it is append-only for write but random read.
 *  The write performance is more important than read; reads only happen
 *  if the journal grows to the point where it needs to free up cache. 
 */
public final
class Journal implements Iterable<JournalEntry>, Sync, Closeable
{
    private static Logger log = LoggerFactory.getLogger(Journal.class) ;
    
    // Version 1 : issue might be excessive copying
    // [TxTDB:TODO] Caching
    
    // Why synchronized?
    // Object handling - avoid length twice. 
    
    // We want random access AND stream efficiency to write.  Opps.
    // Also means we can't use DataOutputStream etc.
    
    private BufferChannel channel ;
    private long position ;
    // Length, type, fileRef, [block id]
    // Length is length of variable part.
    private static int Overhead = 4*SizeOfInt ;
    private static final int NoId = 5 ;
    
//    byte[] _buffer = new byte[Overhead] ;
//    ByteBuffer header = ByteBuffer.wrap(_buffer) ;
    private ByteBuffer header = ByteBuffer.allocate(Overhead) ;
    private static int SizeofCRC = SizeOfInt ;
    private ByteBuffer crcTrailer = ByteBuffer.allocate(SizeofCRC) ;    // Adler: 32 bit.
    
    public static boolean exists(Location location)
    {
        if ( location.isMem() ) return false ;
        return FileOps.exists(journalFilename(location)) ;
    }
    
    public static Journal create(Location location)
    {
        BufferChannel chan ;
        if ( location.isMem() )
            chan = BufferChannelMem.create() ;
        else
            chan = new BufferChannelFile(journalFilename(location)) ;
        return new Journal(chan) ;
    }
    
    private static String journalFilename(Location location) { return location.absolute(Names.journalFile) ; }
    
    private Journal(String filename)
    {
        this(new BufferChannelFile(filename)) ;
        
    }
    
    /*testing*/Journal(BufferChannel channel)
    {
        this.channel = channel ;
        position = 0 ;
    }
    
    synchronized
    public long writeJournal(JournalEntry entry)
    {
        long posn = _write(entry.getType(), entry.getFileRef(), entry.getByteBuffer(), entry.getBlock()) ;
        if ( entry.getPosition() < 0 )
        {
            entry.setPosition(posn) ;
            entry.setEndPosition(position) ;
        }
        return posn ;
    }
    
    synchronized
    public long writeJournal(JournalEntryType type, FileRef fileRef, ByteBuffer buffer)
    {
        return _write(type, fileRef, buffer, null) ;
    }
    
    synchronized
    public long writeJournal(FileRef fileRef, Block block)
    {
        return _write(Block, fileRef, null, block) ;
    }
     
    synchronized
    private long _write(JournalEntryType type, FileRef fileRef, ByteBuffer buffer, Block block)
    {
        //log.info("@"+position()+" -- "+type+","+fileRef+", "+buffer+", "+block) ;
        
        if ( buffer != null && block != null )
            throw new TDBTransactionException("Buffer and block to write") ;
        if ( block != null )
            buffer = block.getByteBuffer() ;

        if ( block != null && type != Block )
            throw new TDBTransactionException("Block to write but not block type") ;
        
        long posn = position ;
        int bufferCapacity = 0 ; 
        int len = 0 ;
        
        // [TxDEV:TODO] compress
        // [TxDEV:TODO] Work in blocks - block asn remember, reset position/limit.
        
        if ( buffer != null )
        {
            bufferCapacity = buffer.capacity() ;
            len = buffer.remaining() ;
        }
        
        header.clear() ;
        header.putInt(type.id) ;
        //header.putInt(len) ;
        header.putInt(bufferCapacity) ;     // Write whole buffer.
        header.putInt(fileRef.getId()) ;
        int blkId = (block==null) ? NoId : block.getId().intValue() ;
        header.putInt(blkId) ;
        header.flip() ;
        channel.write(header) ;
        
        Adler32 adler = new Adler32() ;
        adler.update(header.array()) ;

        if ( len > 0 )
        {
            // Make buffer include it's full length.
            // [TxDEV:TODO] This is the full buffer, junk and all.
            // This makes the system able to check block sizes (BlockAccess checking).
            
            int bufferLimit = buffer.limit() ;
            int bufferPosition = buffer.position() ;
            buffer.position(0) ;
            buffer.limit(bufferCapacity) ;
            // Clear top.
            for ( int i = len ; i < bufferCapacity ; i++ )
                buffer.put(i, (byte)0) ;
            
            // Write all bytes
            channel.write(buffer) ;
            adler.update(buffer.array()) ;
            
            buffer.position(bufferPosition) ;
            buffer.limit(bufferLimit) ;
        }

        // checksum
        crcTrailer.clear() ;
        Bytes.setInt((int)adler.getValue(), crcTrailer.array()) ;
        channel.write(crcTrailer) ;

        position += Overhead + len + SizeofCRC ; // header + payload + checksum
        return posn ;
    }
    
    synchronized
    public JournalEntry readJournal(long id)
    {
        return _readJournal(id) ;
    }
    
    private JournalEntry _readJournal(long id)
    {
        long x = channel.position() ;
        if ( x != id ) 
            channel.position(id) ;
        JournalEntry entry = _read() ;
        long x2 = channel.position() ;
        entry.setPosition(id) ;
        entry.setEndPosition(x2) ;

        if ( x != id )
            channel.position(x) ;
        return entry ;
    }
    
    // read one entry at the channel position.
    // Move position to end of read.
    private JournalEntry _read()
    {
        header.clear() ;
        int lenRead = channel.read(header) ;
        if ( lenRead == -1 )
        {
            // probably broken file.
            throw new TDBTransactionException("Read off the end of a journal file") ;
            //return null ;
        }
        header.rewind() ;
        int typeId  = header.getInt() ; 
        int len     = header.getInt() ;
        int ref     = header.getInt() ;
        int blockId = header.getInt() ;
        
        Adler32 adler = new Adler32() ;
        adler.update(header.array()) ;

        JournalEntryType type = JournalEntryType.type(typeId) ;
        FileRef fileRef = FileRef.get(ref) ;
        ByteBuffer bb = ByteBuffer.allocate(len) ;
        Block block = null ;
        lenRead = channel.read(bb) ;
        adler.update(bb.array()) ;
        bb.rewind() ;
        if ( type == Block )
        {
            block = new Block(blockId, bb) ;
            bb = null ;
        }
        else
            blockId = NoId ;

        // checksum
        crcTrailer.clear() ;
        lenRead = channel.read(crcTrailer) ;
        if ( lenRead != SizeofCRC )
            throw new TDBTransactionException("Feaild to read block checksum.") ;
        int checksum = Bytes.getInt(crcTrailer.array()) ;
        if ( checksum != (int)adler.getValue() )
        	throw new TDBTransactionException("Checksum error reading from the Journal.") ;

        return new JournalEntry(type, fileRef, bb, block) ;
    } 

    /** Iterator of entries from current point in Journal, going forward. Must be JournalEntry aligned at start. */
    private class IteratorEntries extends IteratorSlotted<JournalEntry>
    {
        JournalEntry slot = null ;
        final long endPoint ;
        long iterPosn ;

        public IteratorEntries(long startPosition) 
        {
            iterPosn = startPosition ;
            endPoint = channel.size() ;
        }
        
        @Override
        protected JournalEntry moveToNext()
        {
            synchronized(Journal.this)
            {
                if ( iterPosn >= endPoint )
                    return null ;
                JournalEntry e = _readJournal(iterPosn) ;
                iterPosn = e.getEndPosition() ;
                return e ;
            }
        }

        @Override
        protected boolean hasMore()     { return iterPosn < endPoint  ; }
    }
    
    public Iterator<JournalEntry> entries()         { return new IteratorEntries(0) ; }
    
    synchronized
    public Iterator<JournalEntry> entries(long startPosition)
    {
        return new IteratorEntries(startPosition) ;
    }
    
    /** Convenience method to iterate over the whole journal */ 
    @Override
    public Iterator<JournalEntry> iterator() { return entries() ; }

    @Override
    public void sync()  { channel.sync() ; }

    @Override
    public void close() { channel.close() ; }

    public long size()  { return channel.size() ; }
    
    public void truncate(long size) { channel.truncate(size) ; }
    
    public void append()    { position(size()) ; }
    
    public long position() { return channel.position() ; }  
    
    public void position(long posn) { channel.position(posn) ; }
    
    public String getFilename() { return channel.getFilename() ; }
}

/*
 * (c) Copyright 2011 Epimorphics Ltd.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */