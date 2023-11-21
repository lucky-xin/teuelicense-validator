package com.license.validator;

import com.license.validator.auth.V4Authentication;
import com.license.validator.auth.V4RepositoryFactory;
import com.license.validator.codec.V4CodecFactory;
import com.license.validator.crypto.V4Encryption;
import com.license.validator.crypto.V4EncryptionParameters;
import com.license.validator.entity.LicenseKey;
import com.license.validator.entity.LicenseResolver;
import com.license.validator.entity.LicenseToken;
import com.license.validator.store.LicenseStore;
import global.namespace.fun.io.api.Filter;
import global.namespace.fun.io.api.Store;
import global.namespace.fun.io.bios.BIOS;
import global.namespace.truelicense.api.ConsumerLicenseManager;
import global.namespace.truelicense.api.LicenseFunctionComposition;
import global.namespace.truelicense.api.LicenseManagementContext;
import global.namespace.truelicense.core.passwd.MinimumPasswordPolicy;
import global.namespace.truelicense.core.passwd.ObfuscatedPasswordProtection;
import global.namespace.truelicense.obfuscate.ObfuscatedString;
import global.namespace.truelicense.v4.V4;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

/**
 * 自定义LicenseManager，离线线校验
 *
 * @author luchaoxin
 * @version V 1.0
 * @since 2023-11-06
 */
public class OfflineLicenseValidator {
    static final Logger log = LoggerFactory.getLogger(OfflineLicenseValidator.class);

    private final Store license;
    private volatile LicenseToken token;

    private final ConsumerLicenseManager consumerLicenseManager;

    @Setter
    private LicenseStore licenseStore;


    public OfflineLicenseValidator(
            SecretKey secretKey,
            LicenseKey licenseKey,
            String license) throws IOException {
        this.license = BIOS.file(license);
        ObfuscatedString obfuscatedString = new ObfuscatedString(ObfuscatedString.array(licenseKey.getKeyPass()));
        ObfuscatedPasswordProtection protection = new ObfuscatedPasswordProtection(obfuscatedString);
        V4EncryptionParameters parameters = new V4EncryptionParameters(
                secretKey,
                "AES",
                protection
        );
        V4Encryption encryption = new V4Encryption(parameters);
        LicenseManagementContext context = V4.builder()
                .authenticationFactory(V4Authentication::new)
                .cachePeriodMillis(1000L)
                .codecFactory(new V4CodecFactory(encryption))
                .encryptionFactory(p -> encryption)
                .clock(Clock.systemUTC())
                .compression(Filter.IDENTITY)
                .initializationComposition(LicenseFunctionComposition.decorate)
                .passwordPolicy(new MinimumPasswordPolicy())
                .subject(licenseKey.getSubject())
                .validationComposition(LicenseFunctionComposition.decorate)
                .repositoryFactory(new V4RepositoryFactory())
                .build();

        Store clm = BIOS.memory(1024 * 1024);
        byte[] keysStoreBytes = licenseKey.getKeysStoreBytes();
        Store ks = BIOS.memory(keysStoreBytes.length);
        ks.content(keysStoreBytes);
        this.consumerLicenseManager = context.consumer()
                .encryption(encryption)
                .storeInSystemPreferences(OfflineLicenseValidator.class)
                .authentication()
                .storeProtection(protection)
                .keyProtection(protection)
                .alias(licenseKey.getAlias())
                .algorithm(licenseKey.getAlg())
                .loadFrom(ks)
                .up()
                .storeIn(clm)
                .build();
    }

    /**
     * 复写install方法，其中validate方法调用本类中的validate方法，校验IP地址、Mac地址等其他信息
     *
     * @return LicenseContent
     */
    public synchronized LicenseToken install() throws Exception {
        LicenseResolver resolver = new LicenseResolver(license.content());
        consumerLicenseManager.install(resolver.resolve().secret());
        return new LicenseToken(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    public LicenseToken verify() throws Exception {
        if (token == null) {
            synchronized (this) {
                if (token == null) {
                    token = install();
                }
            }
        }
        consumerLicenseManager.verify();
        return token;
    }
}
