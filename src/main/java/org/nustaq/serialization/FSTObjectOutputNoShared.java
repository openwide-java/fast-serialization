package org.nustaq.serialization;

import org.nustaq.serialization.util.FSTUtil;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Copyright (c) 2012, Ruediger Moeller. All rights reserved.
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 * <p/>
 * Date: 02.03.14
 * Time: 13:20
 */
/**
 * Subclass optimized for "unshared mode". Cycles and Objects referenced more than once will not be detected.
 * Additionally JDK compatibility is not supported (read/writeObject and stuff). Use case is highperformance
 * serialization of plain cycle free data (e.g. messaging). Can perform significantly faster (20-40%).
 */
// FIXME: needs adaption to 2.0
public class FSTObjectOutputNoShared extends FSTObjectOutput {

    /**
     * Creates a new FSTObjectOutput stream to write data to the specified
     * underlying output stream.
     * uses Default Configuration singleton
     *
     * @param out
     */
    public FSTObjectOutputNoShared(OutputStream out) {
        super(out);
        objects.disabled = true;
    }

    /**
     * Creates a new FSTObjectOutputNoShared stream to write data to the specified
     * underlying output stream. The counter <code>written</code> is
     * set to zero.
     * Don't create a FSTConfiguration with each stream, just create one global static configuration and reuse it.
     * FSTConfiguration is threadsafe.
     *
     * @param out  the underlying output stream, to be saved for later
     *             use.
     * @param conf
     */
    public FSTObjectOutputNoShared(OutputStream out, FSTConfiguration conf) {
        super(out, conf);
        conf.setShareReferences(false);
        objects.disabled = true;
    }

    /**
     * serialize without an underlying stream, the resulting byte array of writing to
     * this FSTObjectOutput can be accessed using getBuffer(), the size using getWritten().
     * <p/>
     * Don't create a FSTConfiguration with each stream, just create one global static configuration and reuseit.
     * FSTConfiguration is threadsafe.
     *
     * @param conf
     */
    public FSTObjectOutputNoShared(FSTConfiguration conf) {
        super(conf);
        conf.setShareReferences(false);
        objects.disabled = true;
    }

    /**
     * serialize without an underlying stream, the resulting byte array of writing to
     * this FSTObjectOutput can be accessed using getBuffer(), the size using getWritten().
     * <p/>
     * uses default configuration singleton
     *
     */
    public FSTObjectOutputNoShared() {
        super();
    }

    @Override
    protected void writeObjectWithContext(FSTClazzInfo.FSTFieldInfo referencee, Object toWrite) throws IOException {
        int startPosition = codec.getWritten();
        boolean dontShare = true;
        objectWillBeWritten(toWrite,startPosition);

        try {
            if ( toWrite == null ) {
                codec.writeTag(NULL, null, 0, toWrite);
                return;
            }
            final Class clazz = toWrite.getClass();
            if ( clazz == String.class ) {
                String[] oneOf = referencee.getOneOf();
                if ( oneOf != null ) {
                    for (int i = 0; i < oneOf.length; i++) {
                        String s = oneOf[i];
                        if ( s.equals(toWrite) ) {
                            codec.writeTag(ONE_OF, oneOf, i, toWrite);
                            codec.writeFByte(i);
                            return;
                        }
                    }
                }
                if (dontShare) {
                    codec.writeTag(STRING, toWrite, 0, toWrite);
                    codec.writeStringUTF((String) toWrite);
                    return;
                }
            } else if ( clazz == Integer.class ) {
                codec.writeTag(BIG_INT, null, 0, toWrite);
                codec.writeFInt(((Integer) toWrite).intValue()); return;
            } else if ( clazz == Long.class ) {
                codec.writeTag(BIG_LONG, null, 0, toWrite);
                codec.writeFLong(((Long) toWrite).longValue()); return;
            } else if ( clazz == Boolean.class ) {
                codec.writeTag(((Boolean) toWrite).booleanValue() ? BIG_BOOLEAN_TRUE : BIG_BOOLEAN_FALSE, null, 0, toWrite); return;
            } else if ( (referencee.getType() != null && referencee.getType().isEnum()) || toWrite instanceof Enum ) {
                if ( ! codec.writeTag(ENUM, toWrite, 0, toWrite) ) {
                    boolean isEnumClass = toWrite.getClass().isEnum();
                    if (!isEnumClass) {
                        // weird stuff ..
                        Class c = toWrite.getClass();
                        while (c != null && !c.isEnum()) {
                            c = toWrite.getClass().getEnclosingClass();
                        }
                        if (c == null) {
                            throw new RuntimeException("Can't handle this enum: " + toWrite.getClass());
                        }
                        codec.writeClass(c);
                    } else {
                        codec.writeClass(getFstClazzInfo(referencee, toWrite.getClass()));
                    }
                    codec.writeFInt(((Enum) toWrite).ordinal());
                }
                return;
            }

            FSTClazzInfo serializationInfo = getFstClazzInfo(referencee, clazz);
            // check for identical / equal objects
            FSTObjectSerializer ser = serializationInfo.getSer();
            if (clazz.isArray()) {
                if (codec.writeTag(ARRAY, toWrite, 0, toWrite))
                    return; // some codecs handle primitive arrays like an primitive type
                writeArray(referencee, toWrite);
            } else if ( ser == null ) {
                // default write object wihtout custom serializer
                // handle write replace
                if (! writeObjectHeader(serializationInfo, referencee, toWrite) ) { // skip in case codec can write object as primitive
                    defaultWriteObject(toWrite, serializationInfo);
                    if ( serializationInfo.isExternalizable() )
                        codec.externalEnd(serializationInfo);
                }
            } else { // object has custom serializer
                // Object header (nothing written till here)
                int pos = codec.getWritten();
                if (! writeObjectHeader(serializationInfo, referencee, toWrite) ) { // skip in case code can write object as primitive
                    // write object depending on type (custom, externalizable, serializable/java, default)
                    ser.writeObject(this, toWrite, serializationInfo, referencee, pos);
                    codec.externalEnd(serializationInfo);
                }
            }
        } finally {
            objectHasBeenWritten(toWrite,startPosition,codec.getWritten());
        }
    }

    public void resetForReUse( OutputStream out ) {
        if ( closed )
            throw new RuntimeException("Can't reuse closed stream");
        codec.reset();
        if ( out != null ) {
            codec.setOutstream(out);
        }
    }

    public void resetForReUse( byte[] out ) {
        if ( closed )
            throw new RuntimeException("Can't reuse closed stream");
        codec.reset();
        codec.reset(out);
    }

}
