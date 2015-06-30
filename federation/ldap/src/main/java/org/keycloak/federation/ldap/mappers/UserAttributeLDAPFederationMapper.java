package org.keycloak.federation.ldap.mappers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;
import org.keycloak.federation.ldap.LDAPFederationProvider;
import org.keycloak.federation.ldap.idm.model.LDAPObject;
import org.keycloak.federation.ldap.idm.query.Condition;
import org.keycloak.federation.ldap.idm.query.QueryParameter;
import org.keycloak.federation.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserFederationMapperModel;
import org.keycloak.models.UserFederationProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.UserModelDelegate;
import org.keycloak.models.utils.reflection.Property;
import org.keycloak.models.utils.reflection.PropertyCriteria;
import org.keycloak.models.utils.reflection.PropertyQueries;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class UserAttributeLDAPFederationMapper extends AbstractLDAPFederationMapper {

    private static final Logger logger = Logger.getLogger(UserAttributeLDAPFederationMapper.class);

    private static final Map<String, Property<Object>> userModelProperties;

    static {
        Map<String, Property<Object>> userModelProps = PropertyQueries.createQuery(UserModel.class).addCriteria(new PropertyCriteria() {

            @Override
            public boolean methodMatches(Method m) {
                if ((m.getName().startsWith("get") || m.getName().startsWith("is")) && m.getParameterTypes().length > 0) {
                    return false;
                }

                return true;
            }

        }).getResultList();

        // Convert to be keyed by lower-cased attribute names
        userModelProperties = new HashMap<>();
        for (Map.Entry<String, Property<Object>> entry : userModelProps.entrySet()) {
            userModelProperties.put(entry.getKey().toLowerCase(), entry.getValue());
        }
    }

    public static final String USER_MODEL_ATTRIBUTE = "user.model.attribute";
    public static final String LDAP_ATTRIBUTE = "ldap.attribute";
    public static final String READ_ONLY = "read.only";
    public static final String ALWAYS_READ_VALUE_FROM_LDAP = "always.read.value.from.ldap";


    @Override
    public void onImportUserFromLDAP(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        Property<Object> userModelProperty = userModelProperties.get(userModelAttrName.toLowerCase());

        if (userModelProperty != null) {

            // we have java property on UserModel
            String ldapAttrValue = ldapUser.getAttributeAsString(ldapAttrName);
            setPropertyOnUserModel(userModelProperty, user, ldapAttrValue);
        } else {

            // we don't have java property. Let's set attribute
            Set<String> ldapAttrValue = ldapUser.getAttributeAsSet(ldapAttrName);
            if (ldapAttrValue != null) {
                user.setAttribute(userModelAttrName, new ArrayList<>(ldapAttrValue));
            } else {
                user.removeAttribute(userModelAttrName);
            }
        }
    }

    @Override
    public void onRegisterUserToLDAP(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, LDAPObject ldapUser, UserModel localUser, RealmModel realm) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        Property<Object> userModelProperty = userModelProperties.get(userModelAttrName.toLowerCase());

        if (userModelProperty != null) {

            // we have java property on UserModel. Assuming we support just properties of simple types
            Object attrValue = userModelProperty.getValue(localUser);
            String valueAsString = (attrValue == null) ? null : attrValue.toString();
            ldapUser.setSingleAttribute(ldapAttrName, valueAsString);
        } else {

            // we don't have java property. Let's set attribute
            List<String> attrValues = localUser.getAttribute(userModelAttrName);

            if (attrValues.size() == 0) {
                ldapUser.setAttribute(ldapAttrName, null);
            } else {
                ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<>(attrValues));
            }
        }

        if (isReadOnly(mapperModel)) {
            ldapUser.addReadOnlyAttributeName(ldapAttrName);
        }
    }

    @Override
    public UserModel proxy(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, final LDAPObject ldapUser, UserModel delegate, RealmModel realm) {
        final String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        final String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);
        boolean isAlwaysReadValueFromLDAP = parseBooleanParameter(mapperModel, ALWAYS_READ_VALUE_FROM_LDAP);

        // For writable mode, we want to propagate writing of attribute to LDAP as well
        if (ldapProvider.getEditMode() == UserFederationProvider.EditMode.WRITABLE && !isReadOnly(mapperModel)) {

            delegate = new TxAwareLDAPUserModelDelegate(delegate, ldapProvider, ldapUser) {

                @Override
                public void setSingleAttribute(String name, String value) {
                    setLDAPAttribute(name, value);
                    super.setSingleAttribute(name, value);
                }

                @Override
                public void setAttribute(String name, List<String> values) {
                    setLDAPAttribute(name, values);
                    super.setAttribute(name, values);
                }

                @Override
                public void removeAttribute(String name) {
                    setLDAPAttribute(name, null);
                    super.removeAttribute(name);
                }

                @Override
                public void setEmail(String email) {
                    setLDAPAttribute(UserModel.EMAIL, email);
                    super.setEmail(email);
                }

                @Override
                public void setLastName(String lastName) {
                    setLDAPAttribute(UserModel.LAST_NAME, lastName);
                    super.setLastName(lastName);
                }

                @Override
                public void setFirstName(String firstName) {
                    setLDAPAttribute(UserModel.FIRST_NAME, firstName);
                    super.setFirstName(firstName);
                }

                protected void setLDAPAttribute(String modelAttrName, Object value) {
                    if (modelAttrName.equalsIgnoreCase(userModelAttrName)) {
                        if (logger.isTraceEnabled()) {
                            logger.tracef("Pushing user attribute to LDAP. username: %s, Model attribute name: %s, LDAP attribute name: %s, Attribute value: %s", getUsername(), modelAttrName, ldapAttrName, value);
                        }

                        ensureTransactionStarted();

                        if (value == null) {
                            ldapUser.setAttribute(ldapAttrName, null);
                        } else if (value instanceof String) {
                            ldapUser.setSingleAttribute(ldapAttrName, (String) value);
                        } else {
                            List<String> asList = (List<String>) value;
                            ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<>(asList));
                        }
                    }
                }

            };

        }

        // We prefer to read attribute value from LDAP instead of from local Keycloak DB
        if (isAlwaysReadValueFromLDAP) {

            delegate = new UserModelDelegate(delegate) {

                @Override
                public String getFirstAttribute(String name) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getFirstAttribute(name);
                    }
                }

                @Override
                public List<String> getAttribute(String name) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        Collection<String> ldapAttrValue = ldapUser.getAttributeAsSet(ldapAttrName);
                        if (ldapAttrValue == null) {
                            return null;
                        } else {
                            return new ArrayList<>(ldapAttrValue);
                        }
                    } else {
                        return super.getAttribute(name);
                    }
                }

                @Override
                public Map<String, List<String>> getAttributes() {
                    Map<String, List<String>> attrs = new HashMap<>(super.getAttributes());

                    // Ignore UserModel properties
                    if (userModelProperties.get(userModelAttrName.toLowerCase()) != null) {
                        return attrs;
                    }

                    Set<String> allLdapAttrValues = ldapUser.getAttributeAsSet(ldapAttrName);
                    if (allLdapAttrValues != null) {
                        attrs.put(userModelAttrName, new ArrayList<>(allLdapAttrValues));
                    }
                    return attrs;
                }

                @Override
                public String getEmail() {
                    if (UserModel.EMAIL.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getEmail();
                    }
                }

                @Override
                public String getLastName() {
                    if (UserModel.LAST_NAME.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getLastName();
                    }
                }

                @Override
                public String getFirstName() {
                    if (UserModel.FIRST_NAME.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getFirstName();
                    }
                }

            };
        }

        return delegate;
    }

    @Override
    public void beforeLDAPQuery(UserFederationMapperModel mapperModel, LDAPQuery query) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        // Add mapped attribute to returning ldap attributes
        query.addReturningLdapAttribute(ldapAttrName);
        if (isReadOnly(mapperModel)) {
            query.addReturningReadOnlyLdapAttribute(ldapAttrName);
        }

        // Change conditions and use ldapAttribute instead of userModel
        for (Condition condition : query.getConditions()) {
            QueryParameter param = condition.getParameter();
            if (param != null && param.getName().equalsIgnoreCase(userModelAttrName)) {
                param.setName(ldapAttrName);
            }
        }
    }

    private boolean isReadOnly(UserFederationMapperModel mapperModel) {
        return parseBooleanParameter(mapperModel, READ_ONLY);
    }


    protected void setPropertyOnUserModel(Property<Object> userModelProperty, UserModel user, String ldapAttrValue) {
        if (ldapAttrValue == null) {
            userModelProperty.setValue(user, null);
        } else {
            Class<Object> clazz = userModelProperty.getJavaClass();

            if (String.class.equals(clazz)) {
                userModelProperty.setValue(user, ldapAttrValue);
            } else if (Boolean.class.equals(clazz) || boolean.class.equals(clazz)) {
                Boolean boolVal = Boolean.valueOf(ldapAttrValue);
                userModelProperty.setValue(user, boolVal);
            } else {
                logger.warnf("Don't know how to set the property '%s' on user '%s' . Value of LDAP attribute is '%s' ", userModelProperty.getName(), user.getUsername(), ldapAttrValue.toString());
            }
        }
    }
}
