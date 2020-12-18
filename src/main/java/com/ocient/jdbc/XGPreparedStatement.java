package com.ocient.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;

public class XGPreparedStatement extends XGStatement implements PreparedStatement {
  private static final Logger LOGGER = Logger.getLogger("com.ocient.jdbc");
  private final String sql;

  public XGPreparedStatement(
      final XGConnection conn, final String sql, final boolean force, final boolean oneShotForce)
      throws SQLException {
    super(conn, force, oneShotForce);
    this.sql = sql;
  }

  public XGPreparedStatement(
      final XGConnection conn,
      final String sql,
      final int arg1,
      final int arg2,
      final boolean force,
      final boolean oneShotForce)
      throws SQLException {
    super(conn, arg1, arg2, force, oneShotForce);
    this.sql = sql;
  }

  public XGPreparedStatement(
      final XGConnection conn,
      final String sql,
      final int arg1,
      final int arg2,
      final int arg3,
      final boolean force,
      final boolean oneShotForce)
      throws SQLException {
    super(conn, arg1, arg2, arg3, force, oneShotForce);
    this.sql = sql;
  }

  @Override
  public void addBatch() throws SQLException {
    LOGGER.log(Level.INFO, "Called addBatch()");
    addBatch(sql);
  }

  @Override
  public void clearParameters() throws SQLException {
    LOGGER.log(Level.INFO, "Called clearParameters()");
    if (closed) {
      LOGGER.log(Level.WARNING, "clearParameters() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    parms.clear();
  }

  @Override
  public boolean execute() throws SQLException {
    LOGGER.log(Level.INFO, "Called execute()");
    return execute(sql);
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    LOGGER.log(Level.INFO, "Called executeQuery()");
    return executeQuery(sql);
  }

  @Override
  public int executeUpdate() throws SQLException {
    LOGGER.log(Level.INFO, "Called executeUpdate()");
    return executeUpdate(sql);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    LOGGER.log(Level.WARNING, "Called getMetaData(), returning null");
    return null;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    LOGGER.log(Level.WARNING, "getParameterMetaData() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setArray(final int parameterIndex, final Array x) throws SQLException {
    LOGGER.log(Level.WARNING, "setArray() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x) throws SQLException {
    LOGGER.log(Level.WARNING, "setAsciiStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final int length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setAsciiStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setAsciiStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBigDecimal(final int parameterIndex, final BigDecimal x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setBigDecimal()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setBigDecimal() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    if (x.scale() >= 0) {
      parms.add(parameterIndex - 1, x);
    } else {
      parms.add(parameterIndex - 1, x.setScale(0));
    }
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x) throws SQLException {
    LOGGER.log(Level.WARNING, "setBinaryStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final int length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setBinaryStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setBinaryStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(final int parameterIndex, final Blob x) throws SQLException {
    LOGGER.log(Level.WARNING, "setBlob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream) throws SQLException {
    LOGGER.log(Level.WARNING, "setBlob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setBlob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBoolean(final int parameterIndex, final boolean x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setBoolean()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setBoolean() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setByte(final int parameterIndex, final byte x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setByte()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setByte() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setBytes(final int parameterIndex, final byte[] x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setBytes()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setBytes() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setCharacterStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader, final int length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setCharacterStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setCharacterStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(final int parameterIndex, final Clob x) throws SQLException {
    LOGGER.log(Level.WARNING, "setClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader) throws SQLException {
    LOGGER.log(Level.WARNING, "setClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setDate(final int parameterIndex, final Date x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setDate()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setDate() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setDate(final int parameterIndex, final Date x, final Calendar cal)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setDate() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setDouble(final int parameterIndex, final double x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setDouble()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setDouble() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setFloat(final int parameterIndex, final float x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setFloat()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setFloat() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setInt(final int parameterIndex, final int x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setInt()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setInt() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setLong(final int parameterIndex, final long x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setLong()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setLong() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setNCharacterStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setNCharacterStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(final int parameterIndex, final NClob value) throws SQLException {
    LOGGER.log(Level.WARNING, "setNClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader) throws SQLException {
    LOGGER.log(Level.WARNING, "setNClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setNClob() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNString(final int parameterIndex, final String value) throws SQLException {
    LOGGER.log(Level.WARNING, "setNString() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNull(final int parameterIndex, final int sqlType) throws SQLException {
    LOGGER.log(Level.INFO, "Called setNull()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setNull() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, null);
  }

  @Override
  public void setNull(final int parameterIndex, final int sqlType, final String typeName)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setNull() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setObject(final int parameterIndex, final Object x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setObject()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setObject() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setObject(final int parameterIndex, final Object x, final int targetSqlType)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setObject() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setObject(
      final int parameterIndex, final Object x, final int targetSqlType, final int scaleOrLength)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setObject() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRef(final int parameterIndex, final Ref x) throws SQLException {
    LOGGER.log(Level.WARNING, "setRef() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRowId(final int parameterIndex, final RowId x) throws SQLException {
    LOGGER.log(Level.WARNING, "setRowId() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setShort(final int parameterIndex, final short x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setShort()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setShort() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setSQLXML(final int parameterIndex, final SQLXML xmlObject) throws SQLException {
    LOGGER.log(Level.WARNING, "setSQLXML() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setString(final int parameterIndex, final String x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setString()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setString() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setTime(final int parameterIndex, final Time x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setTime()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setTime() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setTime(final int parameterIndex, final Time x, final Calendar cal)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setTime() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x) throws SQLException {
    LOGGER.log(Level.INFO, "Called setTimestamp()");
    if (closed) {
      LOGGER.log(Level.WARNING, "setTimestamp() is throwing CALL_ON_CLOSED_OBJECT");
      throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
    }

    while (parameterIndex - 1 > parms.size()) {
      parms.add(null);
    }

    parms.add(parameterIndex - 1, x);
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x, final Calendar cal)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setTimestamp() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setUnicodeStream(final int parameterIndex, final InputStream x, final int length)
      throws SQLException {
    LOGGER.log(Level.WARNING, "setUnicodeStream() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setURL(final int parameterIndex, final URL x) throws SQLException {
    LOGGER.log(Level.WARNING, "setURL() was called, which is not supported");
    throw new SQLFeatureNotSupportedException();
  }
}
