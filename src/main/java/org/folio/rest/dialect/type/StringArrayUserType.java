package org.folio.rest.dialect.type;

import static java.sql.Types.ARRAY;

import java.io.Serializable;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.lang3.ArrayUtils;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

public class StringArrayUserType implements UserType {

  private static final String VARCHAR = "VARCHAR";

  public static final StringArrayUserType INSTANCE = new StringArrayUserType();

  @Override
  public Object assemble(final Serializable cached, final Object owner) throws HibernateException {
    return cached;
  }

  @Override
  public Object deepCopy(final Object o) throws HibernateException {
    return o == null ? null : ((String[]) o).clone();
  }

  @Override
  public Serializable disassemble(final Object o) throws HibernateException {
    return (Serializable) o;
  }

  @Override
  public boolean equals(final Object x, final Object y) throws HibernateException {
    return x == null ? y == null : x.equals(y);
  }

  @Override
  public int hashCode(final Object o) throws HibernateException {
    return o == null ? 0 : o.hashCode();
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Object replace(final Object original, final Object target, final Object owner) throws HibernateException {
    return original;
  }

  @Override
  public Class<String[]> returnedClass() {
    return String[].class;
  }

  @Override
  public int[] sqlTypes() {
    return new int[] { ARRAY };
  }

  @Override
  public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    Array array = rs.getArray(names[0]);
    if (array == null) {
      return null;
    }
    String[] strings = ArrayUtils.toStringArray((Object[]) array.getArray());
    return ArrayUtils.toPrimitive(strings);
  }

  @Override
  public void nullSafeSet(PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    Connection connection = st.getConnection();

    if (value == null) {
      st.setNull(index, sqlTypes()[0]);
    } else {
      String[] strings = ArrayUtils.toStringArray((Object[]) value);
      Array array = connection.createArrayOf(VARCHAR, strings);
      st.setArray(index, array);
    }
  }

}