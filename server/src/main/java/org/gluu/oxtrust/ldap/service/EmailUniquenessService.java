package org.gluu.oxtrust.ldap.service;

import java.io.Serializable;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;

import org.gluu.ldap.model.MailUniquenessConfiguration;
import org.gluu.persist.PersistenceEntryManager;

@Stateless
@Named("emailUniquenessService")
public class EmailUniquenessService implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6252001175748276716L;

	@Inject
	private PersistenceEntryManager ldapEntryManager;

	public boolean isUniquenessEnable() {
		MailUniquenessConfiguration conf = ldapEntryManager.find("cn=Unique mail address,cn=Plugins,cn=config",
				MailUniquenessConfiguration.class, null);
		return conf.isEnabled();
	}

	public boolean setEmailUniqueness(boolean value) {
		MailUniquenessConfiguration conf = new MailUniquenessConfiguration();
		conf.setDn("cn=Unique mail address,cn=Plugins,cn=config");
		conf.setEnabled(value);
		ldapEntryManager.merge(conf);
		return ldapEntryManager
				.find("cn=Unique mail address,cn=Plugins,cn=config", MailUniquenessConfiguration.class, null)
				.isEnabled();
	}

}
