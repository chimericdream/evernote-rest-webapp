package net.ttddyy.evernote.rest;

import com.evernote.auth.EvernoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.social.evernote.api.Evernote;
import org.springframework.social.evernote.api.impl.EvernoteTemplate;
import org.springframework.social.evernote.connect.EvernoteConnectionFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.WebRequest;

/**
 * @author Tadaya Tsuyukubo
 */
@ComponentScan
@Configuration
@EnableAutoConfiguration
@EnableConfigurationProperties
public class ApplicationConfig {

	@Autowired
	public EvernotePropertiesConfiguration evernotePropertiesConfiguration;


	@Configuration
	@ConfigurationProperties(name = "evernote")
	public static class EvernotePropertiesConfiguration {

		public String consumerKey;
		public String consumerSecret;
		public String accessToken;
		public boolean alwaysUseTokenFromConfig;
		public boolean fallbackToTokenFromConfig;
		public EvernoteService environment = EvernoteService.SANDBOX;  // default is sandbox

		public void setEnvironment(EvernoteService environment) {
			this.environment = environment;
		}

		public void setConsumerKey(String consumerKey) {
			this.consumerKey = consumerKey;
		}

		public void setConsumerSecret(String consumerSecret) {
			this.consumerSecret = consumerSecret;
		}

		public void setAccessToken(String accessToken) {
			this.accessToken = accessToken;
		}

		public void setAlwaysUseTokenFromConfig(boolean alwaysUseTokenFromConfig) {
			this.alwaysUseTokenFromConfig = alwaysUseTokenFromConfig;
		}

		public void setFallbackToTokenFromConfig(boolean fallbackToTokenFromConfig) {
			this.fallbackToTokenFromConfig = fallbackToTokenFromConfig;
		}
	}


	@Bean
	public EvernoteConnectionFactory evernoteConnectionFactory() {
		final String consumerKey = this.evernotePropertiesConfiguration.consumerKey;
		final String consumerSecret = this.evernotePropertiesConfiguration.consumerSecret;
		final EvernoteService evernoteService = this.evernotePropertiesConfiguration.environment;
		return new EvernoteConnectionFactory(consumerKey, consumerSecret, evernoteService);
	}

	@Bean
	@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.INTERFACES)
	public Evernote evernote(WebRequest request) {
		final EvernotePropertiesConfiguration config = this.evernotePropertiesConfiguration;
		final EvernoteService evernoteService = config.environment;

		final Evernote evernote;
		if (config.alwaysUseTokenFromConfig) {
			evernote = new EvernoteTemplate(evernoteService, config.accessToken);
		} else {
			String accessToken = request.getHeader("evernote-rest-accesstoken");
			if (accessToken == null && config.fallbackToTokenFromConfig) {
				accessToken = config.accessToken; // fallback to accesstoken from config
			}

			final String noteStoreUrl = request.getHeader("evernote-rest-notestoreurl");
			final String webApiUrlPrefix = request.getHeader("evernote-rest-webapiurlprefix");
			final String userId = request.getHeader("evernote-rest-userid");

			if (noteStoreUrl != null && webApiUrlPrefix != null && userId != null) {
				evernote = new EvernoteTemplate(evernoteService, accessToken, noteStoreUrl, webApiUrlPrefix, userId);
			} else {
				evernote = new EvernoteTemplate(evernoteService, accessToken);
			}
		}

		// for this rest app, do not create proxy for thrift object
		evernote.setApplyNullSafe(false);

		return evernote;
	}

}