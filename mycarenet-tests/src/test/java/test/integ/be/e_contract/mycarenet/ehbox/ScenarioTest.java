/*
 * Java MyCareNet Project.
 * Copyright (C) 2013-2014 e-Contract.be BVBA.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package test.integ.be.e_contract.mycarenet.ehbox;

import static org.junit.Assert.assertNotNull;

import java.io.FileInputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.util.ByteArrayDataSource;
import javax.security.auth.x500.X500Principal;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

import test.integ.be.e_contract.mycarenet.Config;
import be.e_contract.mycarenet.ehbox.EHealthBoxConsultationClient;
import be.e_contract.mycarenet.ehbox.EHealthBoxPublicationClient;
import be.e_contract.mycarenet.ehbox.SOAPAttachmentUnmarshaller;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ConsultationContentType;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ConsultationDocumentType;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ConsultationMessageType;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.GetFullMessageResponseType;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.GetMessageListResponseType;
import be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.GetMessageListResponseType.Message;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.ContentContextType;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.ContentSpecificationType;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.DestinationContextType;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.ObjectFactory;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.PublicationContentType;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.PublicationDocumentType;
import be.e_contract.mycarenet.ehbox.jaxb.publication.protocol.PublicationMessageType;
import be.e_contract.mycarenet.sts.Attribute;
import be.e_contract.mycarenet.sts.AttributeDesignator;
import be.e_contract.mycarenet.sts.EHealthSTSClient;
import be.fedict.commons.eid.jca.BeIDProvider;

public class ScenarioTest {

	static final Log LOG = LogFactory.getLog(ScenarioTest.class);

	private Config config;

	@Before
	public void setUp() throws Exception {
		this.config = new Config();
	}

	/**
	 * First we clean the eHealthBox. Then we publish to ourself. Next we
	 * download this message.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testScenario() throws Exception {
		// STS
		EHealthSTSClient client = new EHealthSTSClient(
				"https://wwwacc.ehealth.fgov.be/sts_1_1/SecureTokenService");

		Security.addProvider(new BeIDProvider());
		KeyStore keyStore = KeyStore.getInstance("BeID");
		keyStore.load(null);
		PrivateKey authnPrivateKey = (PrivateKey) keyStore.getKey(
				"Authentication", null);
		X509Certificate authnCertificate = (X509Certificate) keyStore
				.getCertificate("Authentication");

		KeyStore eHealthKeyStore = KeyStore.getInstance("PKCS12");
		FileInputStream fileInputStream = new FileInputStream(
				this.config.getEHealthPKCS12Path());
		eHealthKeyStore.load(fileInputStream, this.config
				.getEHealthPKCS12Password().toCharArray());
		Enumeration<String> aliasesEnum = eHealthKeyStore.aliases();
		String alias = aliasesEnum.nextElement();
		X509Certificate eHealthCertificate = (X509Certificate) eHealthKeyStore
				.getCertificate(alias);
		PrivateKey eHealthPrivateKey = (PrivateKey) eHealthKeyStore.getKey(
				alias, this.config.getEHealthPKCS12Password().toCharArray());

		List<Attribute> attributes = new LinkedList<Attribute>();
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));

		List<AttributeDesignator> attributeDesignators = new LinkedList<AttributeDesignator>();
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:certified-namespace:ehealth",
				"urn:be:fgov:person:ssin:nurse:boolean"));

		Element assertion = client.requestAssertion(authnCertificate,
				authnPrivateKey, eHealthCertificate, eHealthPrivateKey,
				attributes, attributeDesignators);

		assertNotNull(assertion);

		String assertionString = client.toString(assertion);

		// eHealthBox: remove all messages.
		EHealthBoxConsultationClient eHealthBoxClient = new EHealthBoxConsultationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxConsultation/v3");
		eHealthBoxClient.setCredentials(eHealthPrivateKey, assertionString);

		GetMessageListResponseType messageList = eHealthBoxClient
				.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			eHealthBoxClient.deleteMessage(messageId);
		}

		// eHealthBox: publish via SOAP attachment
		EHealthBoxPublicationClient publicationClient = new EHealthBoxPublicationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxPublication/v3");

		ObjectFactory objectFactory = new ObjectFactory();
		PublicationMessageType publicationMessage = objectFactory
				.createPublicationMessageType();
		String publicationId = UUID.randomUUID().toString().substring(1, 13);
		LOG.debug("publication id: " + publicationId);
		publicationMessage.setPublicationId(publicationId);

		DestinationContextType destinationContext = objectFactory
				.createDestinationContextType();
		publicationMessage.getDestinationContext().add(destinationContext);
		destinationContext.setQuality("NURSE");
		destinationContext.setType("INSS");
		destinationContext.setId(getUserIdentifier(authnCertificate));

		ContentContextType contentContext = objectFactory
				.createContentContextType();
		publicationMessage.setContentContext(contentContext);

		PublicationContentType publicationContent = objectFactory
				.createPublicationContentType();
		contentContext.setContent(publicationContent);
		PublicationDocumentType publicationDocument = objectFactory
				.createPublicationDocumentType();
		publicationContent.setDocument(publicationDocument);
		publicationDocument.setTitle("test");
		publicationDocument.setMimeType("application/octet-stream");
		publicationDocument.setDownloadFileName("test.dat");
		byte[] data = new byte[1024 * 256];
		DataSource dataSource = new ByteArrayDataSource(data,
				"application/octet-stream");
		DataHandler dataHandler = new DataHandler(dataSource);
		publicationDocument.setEncryptableBinaryContent(dataHandler);
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		byte[] digest = messageDigest.digest(data);
		publicationDocument.setDigest(Base64.encodeBase64String(digest));

		ContentSpecificationType contentSpecification = objectFactory
				.createContentSpecificationType();
		contentContext.setContentSpecification(contentSpecification);
		contentSpecification.setContentType("DOCUMENT");

		publicationClient.setCredentials(eHealthPrivateKey, assertionString);
		publicationClient.publish(publicationMessage);

		Thread.sleep(1000 * 5);

		LOG.debug("GET MESSAGES LIST");
		messageList = eHealthBoxClient.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			LOG.debug("GET FULL MESSAGE");
			GetFullMessageResponseType getFullMessageResponse = eHealthBoxClient
					.getMessage(messageId);
			ConsultationMessageType consultationMessage = getFullMessageResponse
					.getMessage();
			be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ContentContextType consultationContentContext = consultationMessage
					.getContentContext();
			ConsultationContentType consultationContent = consultationContentContext
					.getContent();
			ConsultationDocumentType consultationDocument = consultationContent
					.getDocument();
			byte[] encryptableTextContent = consultationDocument
					.getEncryptableTextContent();
			if (null != encryptableTextContent) {
				LOG.debug("result EncryptableTextContent: "
						+ encryptableTextContent.length);
			} else {
				LOG.debug("no EncryptableTextContent");
			}
			DataHandler resultDataHandler = consultationDocument
					.getEncryptableBinaryContent();
			if (null != resultDataHandler) {
				LOG.debug("result EncryptableBinaryContent");
				byte[] resultData = IOUtils.toByteArray(resultDataHandler
						.getInputStream());
				LOG.debug("result data size: " + resultData.length);
			}
			LOG.debug("DELETE MESSAGE");
			eHealthBoxClient.deleteMessage(messageId);
		}
	}

	/**
	 * First we clean the eHealthBox. Then we publish to ourself. Next we
	 * download this message.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testScenarioInvoke() throws Exception {
		// STS
		EHealthSTSClient client = new EHealthSTSClient(
				"https://wwwacc.ehealth.fgov.be/sts_1_1/SecureTokenService");

		Security.addProvider(new BeIDProvider());
		KeyStore keyStore = KeyStore.getInstance("BeID");
		keyStore.load(null);
		PrivateKey authnPrivateKey = (PrivateKey) keyStore.getKey(
				"Authentication", null);
		X509Certificate authnCertificate = (X509Certificate) keyStore
				.getCertificate("Authentication");

		KeyStore eHealthKeyStore = KeyStore.getInstance("PKCS12");
		FileInputStream fileInputStream = new FileInputStream(
				this.config.getEHealthPKCS12Path());
		eHealthKeyStore.load(fileInputStream, this.config
				.getEHealthPKCS12Password().toCharArray());
		Enumeration<String> aliasesEnum = eHealthKeyStore.aliases();
		String alias = aliasesEnum.nextElement();
		X509Certificate eHealthCertificate = (X509Certificate) eHealthKeyStore
				.getCertificate(alias);
		PrivateKey eHealthPrivateKey = (PrivateKey) eHealthKeyStore.getKey(
				alias, this.config.getEHealthPKCS12Password().toCharArray());

		List<Attribute> attributes = new LinkedList<Attribute>();
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));

		List<AttributeDesignator> attributeDesignators = new LinkedList<AttributeDesignator>();
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:certified-namespace:ehealth",
				"urn:be:fgov:person:ssin:nurse:boolean"));

		Element assertion = client.requestAssertion(authnCertificate,
				authnPrivateKey, eHealthCertificate, eHealthPrivateKey,
				attributes, attributeDesignators);

		assertNotNull(assertion);

		String assertionString = client.toString(assertion);

		// eHealthBox: remove all messages.
		EHealthBoxConsultationClient eHealthBoxClient = new EHealthBoxConsultationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxConsultation/v3");
		eHealthBoxClient.setCredentials(eHealthPrivateKey, assertionString);

		GetMessageListResponseType messageList = eHealthBoxClient
				.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			eHealthBoxClient.deleteMessage(messageId);
		}

		// eHealthBox: publish via SOAP attachment
		EHealthBoxPublicationClient publicationClient = new EHealthBoxPublicationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxPublication/v3");

		ObjectFactory objectFactory = new ObjectFactory();
		PublicationMessageType publicationMessage = objectFactory
				.createPublicationMessageType();
		String publicationId = UUID.randomUUID().toString().substring(1, 13);
		LOG.debug("publication id: " + publicationId);
		publicationMessage.setPublicationId(publicationId);

		DestinationContextType destinationContext = objectFactory
				.createDestinationContextType();
		publicationMessage.getDestinationContext().add(destinationContext);
		destinationContext.setQuality("NURSE");
		destinationContext.setType("INSS");
		destinationContext.setId(getUserIdentifier(authnCertificate));

		ContentContextType contentContext = objectFactory
				.createContentContextType();
		publicationMessage.setContentContext(contentContext);

		PublicationContentType publicationContent = objectFactory
				.createPublicationContentType();
		contentContext.setContent(publicationContent);
		PublicationDocumentType publicationDocument = objectFactory
				.createPublicationDocumentType();
		publicationContent.setDocument(publicationDocument);
		publicationDocument.setTitle("test");
		publicationDocument.setMimeType("application/octet-stream");
		publicationDocument.setDownloadFileName("test.dat");
		byte[] data = new byte[1024 * 256];
		DataSource dataSource = new ByteArrayDataSource(data,
				"application/octet-stream");
		DataHandler dataHandler = new DataHandler(dataSource);
		publicationDocument.setEncryptableBinaryContent(dataHandler);
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		byte[] digest = messageDigest.digest(data);
		publicationDocument.setDigest(Base64.encodeBase64String(digest));

		ContentSpecificationType contentSpecification = objectFactory
				.createContentSpecificationType();
		contentContext.setContentSpecification(contentSpecification);
		contentSpecification.setContentType("DOCUMENT");

		publicationClient.setCredentials(eHealthPrivateKey, assertionString);
		publicationClient.publish(publicationMessage);

		// give eHealthBox some time.
		Thread.sleep(1000 * 5);

		LOG.debug("GET MESSAGES LIST");
		messageList = eHealthBoxClient.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			LOG.debug("GET FULL MESSAGE");
			String request = "<ehbox:GetFullMessageRequest xmlns:ehbox=\"urn:be:fgov:ehealth:ehbox:consultation:protocol:v3\">"
					+ "<Source>INBOX</Source>"
					+ "<MessageId>"
					+ messageId
					+ "</MessageId>" + "</ehbox:GetFullMessageRequest>";
			String response = eHealthBoxClient.invoke(request);
			LOG.debug("RESPONSE: " + response);
			JAXBContext consultationContext = JAXBContext
					.newInstance(be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ObjectFactory.class);
			Unmarshaller consultationUnmarshaller = consultationContext
					.createUnmarshaller();
			Map<String, DataHandler> messageAttachments = eHealthBoxClient
					.getMessageAttachments();
			consultationUnmarshaller
					.setAttachmentUnmarshaller(new SOAPAttachmentUnmarshaller(
							messageAttachments));
			JAXBElement<GetFullMessageResponseType> jaxbElement = (JAXBElement<GetFullMessageResponseType>) consultationUnmarshaller
					.unmarshal(new StringReader(response));
			GetFullMessageResponseType getFullMessageResponse = jaxbElement
					.getValue();
			ConsultationMessageType consultationMessage = getFullMessageResponse
					.getMessage();
			be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ContentContextType consultationContentContext = consultationMessage
					.getContentContext();
			ConsultationContentType consultationContent = consultationContentContext
					.getContent();
			ConsultationDocumentType consultationDocument = consultationContent
					.getDocument();
			byte[] encryptableTextContent = consultationDocument
					.getEncryptableTextContent();
			if (null != encryptableTextContent) {
				LOG.debug("result EncryptableTextContent: "
						+ encryptableTextContent.length);
			} else {
				LOG.debug("no EncryptableTextContent");
			}
			DataHandler resultDataHandler = consultationDocument
					.getEncryptableBinaryContent();
			if (null != resultDataHandler) {
				LOG.debug("result EncryptableBinaryContent");
				byte[] resultData = IOUtils.toByteArray(resultDataHandler
						.getInputStream());
				LOG.debug("result data size: " + resultData.length);
			}
			LOG.debug("DELETE MESSAGE");
			eHealthBoxClient.deleteMessage(messageId);
		}
	}

	/**
	 * First we clean the eHealthBox. Then we publish to ourself. Next we
	 * download this message.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testScenarioInvokePlainText() throws Exception {
		// STS
		EHealthSTSClient client = new EHealthSTSClient(
				"https://wwwacc.ehealth.fgov.be/sts_1_1/SecureTokenService");

		Security.addProvider(new BeIDProvider());
		KeyStore keyStore = KeyStore.getInstance("BeID");
		keyStore.load(null);
		PrivateKey authnPrivateKey = (PrivateKey) keyStore.getKey(
				"Authentication", null);
		X509Certificate authnCertificate = (X509Certificate) keyStore
				.getCertificate("Authentication");

		KeyStore eHealthKeyStore = KeyStore.getInstance("PKCS12");
		FileInputStream fileInputStream = new FileInputStream(
				this.config.getEHealthPKCS12Path());
		eHealthKeyStore.load(fileInputStream, this.config
				.getEHealthPKCS12Password().toCharArray());
		Enumeration<String> aliasesEnum = eHealthKeyStore.aliases();
		String alias = aliasesEnum.nextElement();
		X509Certificate eHealthCertificate = (X509Certificate) eHealthKeyStore
				.getCertificate(alias);
		PrivateKey eHealthPrivateKey = (PrivateKey) eHealthKeyStore.getKey(
				alias, this.config.getEHealthPKCS12Password().toCharArray());

		List<Attribute> attributes = new LinkedList<Attribute>();
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributes.add(new Attribute("urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));

		List<AttributeDesignator> attributeDesignators = new LinkedList<AttributeDesignator>();
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:ehealth:1.0:certificateholder:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:identification-namespace",
				"urn:be:fgov:person:ssin"));
		attributeDesignators.add(new AttributeDesignator(
				"urn:be:fgov:certified-namespace:ehealth",
				"urn:be:fgov:person:ssin:nurse:boolean"));

		Element assertion = client.requestAssertion(authnCertificate,
				authnPrivateKey, eHealthCertificate, eHealthPrivateKey,
				attributes, attributeDesignators);

		assertNotNull(assertion);

		String assertionString = client.toString(assertion);

		// eHealthBox: remove all messages.
		EHealthBoxConsultationClient eHealthBoxClient = new EHealthBoxConsultationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxConsultation/v3");
		eHealthBoxClient.setCredentials(eHealthPrivateKey, assertionString);

		GetMessageListResponseType messageList = eHealthBoxClient
				.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			eHealthBoxClient.deleteMessage(messageId);
		}

		// eHealthBox: publish
		EHealthBoxPublicationClient publicationClient = new EHealthBoxPublicationClient(
				"https://services-acpt.ehealth.fgov.be/ehBoxPublication/v3");

		ObjectFactory objectFactory = new ObjectFactory();
		PublicationMessageType publicationMessage = objectFactory
				.createPublicationMessageType();
		String publicationId = UUID.randomUUID().toString().substring(1, 13);
		LOG.debug("publication id: " + publicationId);
		publicationMessage.setPublicationId(publicationId);

		DestinationContextType destinationContext = objectFactory
				.createDestinationContextType();
		publicationMessage.getDestinationContext().add(destinationContext);
		destinationContext.setQuality("NURSE");
		destinationContext.setType("INSS");
		destinationContext.setId(getUserIdentifier(authnCertificate));

		ContentContextType contentContext = objectFactory
				.createContentContextType();
		publicationMessage.setContentContext(contentContext);

		PublicationContentType publicationContent = objectFactory
				.createPublicationContentType();
		contentContext.setContent(publicationContent);
		PublicationDocumentType publicationDocument = objectFactory
				.createPublicationDocumentType();
		publicationContent.setDocument(publicationDocument);
		publicationDocument.setTitle("test");
		publicationDocument.setMimeType("text/plain");
		publicationDocument.setDownloadFileName("test.txt");
		byte[] data = "hello world".getBytes();
		publicationDocument.setEncryptableTextContent(data);
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		byte[] digest = messageDigest.digest(data);
		publicationDocument.setDigest(Base64.encodeBase64String(digest));

		ContentSpecificationType contentSpecification = objectFactory
				.createContentSpecificationType();
		contentContext.setContentSpecification(contentSpecification);
		contentSpecification.setContentType("DOCUMENT");

		publicationClient.setCredentials(eHealthPrivateKey, assertionString);
		publicationClient.publish(publicationMessage);

		// give eHealthBox some time.
		Thread.sleep(1000 * 5);

		LOG.debug("GET MESSAGES LIST");
		messageList = eHealthBoxClient.getMessagesList();
		for (Message message : messageList.getMessage()) {
			String messageId = message.getMessageId();
			LOG.debug("message id: " + messageId);
			LOG.debug("GET FULL MESSAGE");
			String request = "<ehbox:GetFullMessageRequest xmlns:ehbox=\"urn:be:fgov:ehealth:ehbox:consultation:protocol:v3\">"
					+ "<Source>INBOX</Source>"
					+ "<MessageId>"
					+ messageId
					+ "</MessageId>" + "</ehbox:GetFullMessageRequest>";
			String response = eHealthBoxClient.invoke(request);
			LOG.debug("RESPONSE: " + response);
			JAXBContext consultationContext = JAXBContext
					.newInstance(be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ObjectFactory.class);
			Unmarshaller consultationUnmarshaller = consultationContext
					.createUnmarshaller();
			Map<String, DataHandler> messageAttachments = eHealthBoxClient
					.getMessageAttachments();
			for (Map.Entry<String, DataHandler> messageAttachment : messageAttachments
					.entrySet()) {
				LOG.debug("message attachment id: "
						+ messageAttachment.getKey());
				LOG.debug("message data handler: "
						+ messageAttachment.getValue());
				DataHandler resultDataHandler = messageAttachment.getValue();
				DataSource resultDataSource = resultDataHandler.getDataSource();
				byte[] attachmentData = IOUtils.toByteArray(resultDataSource
						.getInputStream());
				LOG.debug("DataHandler.DataSource.getInputStream length: "
						+ attachmentData.length);
			}
			consultationUnmarshaller
					.setAttachmentUnmarshaller(new SOAPAttachmentUnmarshaller(
							messageAttachments));
			JAXBElement<GetFullMessageResponseType> jaxbElement = (JAXBElement<GetFullMessageResponseType>) consultationUnmarshaller
					.unmarshal(new StringReader(response));
			GetFullMessageResponseType getFullMessageResponse = jaxbElement
					.getValue();
			ConsultationMessageType consultationMessage = getFullMessageResponse
					.getMessage();
			be.e_contract.mycarenet.ehbox.jaxb.consultation.protocol.ContentContextType consultationContentContext = consultationMessage
					.getContentContext();
			ConsultationContentType consultationContent = consultationContentContext
					.getContent();
			ConsultationDocumentType consultationDocument = consultationContent
					.getDocument();
			byte[] encryptableTextContent = consultationDocument
					.getEncryptableTextContent();
			if (null != encryptableTextContent) {
				LOG.debug("result EncryptableTextContent: "
						+ encryptableTextContent.length);
			} else {
				LOG.debug("no EncryptableTextContent");
			}
			DataHandler resultDataHandler = consultationDocument
					.getEncryptableBinaryContent();
			if (null != resultDataHandler) {
				LOG.debug("result EncryptableBinaryContent");
				byte[] resultData = IOUtils.toByteArray(resultDataHandler
						.getInputStream());
				LOG.debug("result data size: " + resultData.length);
			}
			LOG.debug("DELETE MESSAGE");
			eHealthBoxClient.deleteMessage(messageId);
		}
	}

	private String getUserIdentifier(X509Certificate certificate) {
		X500Principal userPrincipal = certificate.getSubjectX500Principal();
		String name = userPrincipal.toString();
		int serialNumberBeginIdx = name.indexOf("SERIALNUMBER=");
		if (-1 == serialNumberBeginIdx) {
			throw new SecurityException("SERIALNUMBER not found in X509 CN");
		}
		int serialNumberValueBeginIdx = serialNumberBeginIdx
				+ "SERIALNUMBER=".length();
		int serialNumberValueEndIdx = name.indexOf(",",
				serialNumberValueBeginIdx);
		if (-1 == serialNumberValueEndIdx) {
			serialNumberValueEndIdx = name.length();
		}
		String userId = name.substring(serialNumberValueBeginIdx,
				serialNumberValueEndIdx);
		return userId;
	}
}
