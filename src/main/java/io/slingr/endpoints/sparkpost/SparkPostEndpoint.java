package io.slingr.endpoints.sparkpost;

import io.slingr.endpoints.HttpEndpoint;
import io.slingr.endpoints.exceptions.EndpointException;
import io.slingr.endpoints.exceptions.ErrorCode;
import io.slingr.endpoints.framework.annotations.*;
import io.slingr.endpoints.services.AppLogs;
import io.slingr.endpoints.services.HttpService;
import io.slingr.endpoints.services.datastores.DataStore;
import io.slingr.endpoints.services.exchange.Parameter;
import io.slingr.endpoints.utils.Base64Utils;
import io.slingr.endpoints.utils.EmailUtils;
import io.slingr.endpoints.utils.Json;
import io.slingr.endpoints.utils.Strings;
import io.slingr.endpoints.ws.exchange.FunctionRequest;
import io.slingr.endpoints.ws.exchange.WebServiceRequest;
import io.slingr.endpoints.ws.exchange.WebServiceResponse;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SparkPost endpoint
 *
 * Created by lefunes on 21/04/17.
 */
@SlingrEndpoint(name = "sparkpost", functionPrefix = "_")
public class SparkPostEndpoint extends HttpEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(SparkPostEndpoint.class);

    private static final String SPARKPOST_API_URL = "https://api.sparkpost.com/";

    private static final String EVENT_SERVICE = "serviceEvent";
    private static final String EVENT_RESPONSE = "responseArrived";
    private static final String EVENT_EMAIL = "emailArrived";

    private static final int WAITING_RESPONSE_PERIOD = 15 * 24 * 60 * 60 * 1000; // 15 days

    @ApplicationLogger
    private AppLogs appLogger;

    @EndpointDataStore
    private DataStore emails;

    @EndpointProperty
    private String apiKey;

    @EndpointProperty
    private String senderName;

    @EndpointProperty
    private String inboundDomains;

    @EndpointProperty
    private String senderEmail;

    @EndpointProperty
    private String webhookUsername;

    @EndpointProperty
    private String webhookPassword;

    private String sender;
    private String domain;
    private String basicAuth;


    @Override
    public String getApiUri() {
        return SPARKPOST_API_URL;
    }

    @Override
    public void endpointStarted() {
        httpService().setupDefaultHeader("Authorization", apiKey);

        if(StringUtils.isBlank(senderEmail)){
            appLogger.error(String.format("Invalid email domain from send email [%s]", senderEmail));
        } else {
            sender = senderEmail.substring(0, senderEmail.indexOf("@"));
            domain = senderEmail.substring(senderEmail.indexOf("@") + 1);
        }

        final String authToken = Base64Utils.encodeBasicAuthorization(webhookUsername, webhookPassword);
        if (properties().isDebug()) {
            logger.info(String.format("Configured SparkPost endpoint - API key [%s], Sender [%s], Domain [%s], Webhook Auth [%s]", Strings.maskToken(apiKey), sender, domain, Strings.maskToken(authToken)));
        }
        basicAuth = "Basic "+authToken;

        if (StringUtils.isBlank(domain) || !domain.contains(".")) {
            appLogger.error(String.format("Invalid email domain from send email [%s]", senderEmail));
        } else {
            try {
                configureInboundDomains();
            } catch (EndpointException ex){
                appLogger.error(ex.getMessage(), ex);
            }
        }
    }

    @EndpointFunction(name = "_post")
    public Json post(FunctionRequest request){
        // add information about the sender on new transmissions
        final Json jsonBody = request.getJsonParams();
        final String path = jsonBody.string("path");
        if(path.contains("api/v1/transmissions")){
            Json body = jsonBody.json("body");
            if(body != null){
                body = completeTransmissionFrom(body, null);
            }
            if(body != null){
                body.remove("__message_id");
                body = completeTransmissionFiles(body);
            }
            jsonBody.setIfNotNull("body", body);
        }

        // continue with the default processor
        return defaultPostRequest(request);
    }

    @EndpointWebService(path = "/")
    public WebServiceResponse webhookProcessor(WebServiceRequest request){
        final Object auth = request.getHeader("Authorization");
        if(!basicAuth.equals(auth)){
            appLogger.error("Arrives an event from SparkPost with invalid authentication information");
            logger.info(String.format("Event with invalid auth [%s]", auth));
            return HttpService.defaultWebhookResponse("Unauthorized", 401);
        } else {
            // process events
            final Json events = HttpService.defaultWebhookConverter(request);
            final List<Json> eventList = events.jsons("body");
            if(eventList != null && !eventList.isEmpty()){
                for (Json e : eventList) {
                    if(e.contains("msys") && e.json("msys").contains("relay_message")){
                        sendEmailEvent(Json.map().set("body", e));
                    } else {
                        final List<String> ids = new ArrayList<>();
                        if(e.contains("msys")){
                            final Json msys = e.json("msys");
                            for (String mk : msys.keys()) {
                                final Json ev = msys.json(mk);
                                if(ev != null && ev.isNotEmpty()){
                                    checkIdValue(ids, ev, "msg_from");
                                    checkIdValue(ids, ev, "friendly_from");
                                    checkIdValue(ids, ev, "raw_rcpt_to");
                                    checkIdValue(ids, ev, "rcpt_to");
                                    checkIdValue(ids, ev, "mailfrom");
                                }
                            }
                        }

                        events.set("body", e);

                        boolean sent = false;
                        if(!ids.isEmpty()){
                            for (String id : ids) {
                                // find function id
                                final Json message = emails.findById(id);
                                if(message != null && message.contains("functionId")){
                                    final String functionId = message.string("functionId");
                                    if(StringUtils.isNotBlank(functionId)){
                                        // send service as response

                                        events().send(EVENT_SERVICE, events, functionId);
                                        sent = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if(!sent) {
                            events().send(EVENT_SERVICE, events);
                        }
                    }
                }
            } else {
                events().send(EVENT_SERVICE, events);
            }
            return HttpService.defaultWebhookResponse();
        }
    }

    private void checkIdValue(List<String> ids, Json ev, String key) {
        try {
            final String txt = ev.string(key);
            if (StringUtils.isNotBlank(txt) && txt.contains("@") && txt.contains("+")) {
                final String id = txt.substring(txt.indexOf("+") + 1, txt.indexOf("@"));
                if (StringUtils.isNotBlank(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        } catch (Exception ex){
            logger.info(String.format("Exception when try to extract the id from [%s]", key), ex);
        }
    }

    @EndpointWebService(path = "/inbound")
    public WebServiceResponse inboundDomainProcessor(WebServiceRequest request){
        final Json email = HttpService.defaultWebhookConverter(request);

        final List<Json> emails = email.jsons("body");
        if(emails != null && !emails.isEmpty()){
            for (Json e : emails) {
                email.set("body", e);
                sendEmailEvent(email);
            }
        } else {
            sendEmailEvent(email);
        }
        return HttpService.defaultWebhookResponse();
    }

    private void sendEmailEvent(Json email){
        if(email == null){
            email = Json.map();
        }
        boolean response = false;
        if(email.contains("body") && email.json("body").contains("msys") && email.json("body").json("msys").contains("relay_message")){
            final String recipient = email.json("body").json("msys").json("relay_message").string("rcpt_to");
            if(StringUtils.isNotBlank(recipient) && recipient.contains("+") && recipient.contains("@") && recipient.indexOf("+") < recipient.indexOf("@")){
                final String messageId = recipient.substring(recipient.indexOf("+")+1, recipient.indexOf("@"));

                if(StringUtils.isNotBlank(messageId)) {
                    // find function id
                    final Json message = emails.findById(messageId);
                    if(message != null && message.contains("functionId")){
                        final String functionId = message.string("functionId");
                        if(StringUtils.isNotBlank(functionId)){
                            // send email as response

                            events().send(EVENT_RESPONSE, email, functionId);
                            response = true;
                        }
                    }
                }
            }
        }

        if(!response) {
            // send the email event
            events().send(EVENT_EMAIL, email);
        }
    }

    @EndpointFunction(name = "_sendEmail")
    public Json sendEmail(FunctionRequest request){
        String messageId = generateMessageId();

        Json body = request.getJsonParams();
        if(body != null){
            body = completeTransmissionFrom(body, messageId);

            final String newMessageId = body.string("__message_id");
            body.remove("__message_id");
            messageId = StringUtils.isNotBlank(newMessageId) ? newMessageId : messageId;
        }
        if(body != null){
            body = completeTransmissionFiles(body);
        }

        // save on store the function and message ids
        if(StringUtils.isNotBlank(messageId) && StringUtils.isNotBlank(request.getFunctionId())) {
            try {
                emails.save(Json.map()
                        .set("_id", messageId)
                        .set("functionId", request.getFunctionId())
                        .set(Parameter.DATA_STORE_TTL, WAITING_RESPONSE_PERIOD)
                );
            } catch (Exception ex){
                throw EndpointException.permanent(ErrorCode.CLIENT, "Error when try to save message id");
            }
        }

        // continue with the default processor
        return httpService().defaultPostRequest(Json.map()
                .set("path", "api/v1/transmissions")
                .set("body", body)
        );
    }

    @EndpointFunction(name = "_convertToText")
    public Json convertToText(FunctionRequest request){
        final String result = EmailUtils.convertToTextBody(request.getJsonParams().string("value"));
        return Json.map().set("result", result);
    }

    @EndpointFunction(name = "_convertToHtml")
    public Json convertToHtml(FunctionRequest request){
        final String result = EmailUtils.convertToHtml(request.getJsonParams().string("value"));
        return Json.map().set("result", result);
    }

    @EndpointFunction(name = "_extractTextResponse")
    public Json extractTextResponse(FunctionRequest request){
        final String result = EmailUtils.parseTextBody(null, request.getJsonParams().string("value"));
        return Json.map().set("result", result);
    }

    @EndpointFunction(name = "_extractHtmlResponse")
    public Json extractHtmlResponse(FunctionRequest request){
        final String result = EmailUtils.parseHtmlBody(null, request.getJsonParams().string("value"));
        return Json.map().set("result", result);
    }

    @EndpointFunction(name = "_configureInboundDomains")
    public void configureInboundDomains() throws EndpointException {
        // check configuration
        try {
            Json response = httpService().defaultGetRequest(Json.map().set("path", "api/v1/account"));
            if(!"active".equalsIgnoreCase(response.json("results") != null ? response.json("results").string("status") : null)){
                throw EndpointException.permanent(ErrorCode.API, "SlackPost account is not active");
            } else {
                logger.info("SlackPost account is active");

                if(StringUtils.isBlank(inboundDomains)){
                    throw EndpointException.permanent(ErrorCode.API, String.format("There is not configured domains on 'Inbound Domains' field [%s]", inboundDomains));
                } else {
                    final List<String> iDomains = new ArrayList<>();
                    if(inboundDomains.contains(",")){
                        // list of inbound domains
                        final String[] parts = inboundDomains.split(",");
                        for (String part : parts) {
                            if(StringUtils.isNotBlank(part)){
                                iDomains.add(part.trim().toLowerCase());
                            }
                        }
                    } else {
                        iDomains.add(inboundDomains.trim().toLowerCase());
                    }

                    if(iDomains.isEmpty()){
                        throw EndpointException.permanent(ErrorCode.API, String.format("Empty Inbound Domains list [%s]", inboundDomains));
                    } else {
                        logger.info(String.format("Domains to properties [%s]", iDomains));

                        // check domains
                        final List<String> noRegisteredDomains = new ArrayList<>();
                        response = httpService().defaultGetRequest(Json.map().set("path", "api/v1/inbound-domains"));
                        final List<Json> registeredDomains = response.jsons("results");
                        if(registeredDomains != null && !registeredDomains.isEmpty()) {
                            for (String domain : iDomains) {
                                boolean registerDomain = true;
                                for (Json registeredDomain : registeredDomains) {
                                    if(domain.equalsIgnoreCase(registeredDomain.string("domain"))){
                                        registerDomain = false;
                                        logger.info(String.format("Inbound domain [%s] already registered", domain));
                                        break;
                                    }
                                }
                                if(registerDomain){
                                    noRegisteredDomains.add(domain);
                                }
                            }
                        } else {
                            noRegisteredDomains.addAll(iDomains);
                        }

                        // register domains
                        for (String domainToRegister : noRegisteredDomains) {
                            logger.info(String.format("Registering domain [%s]", domainToRegister));
                            httpService().defaultPostRequest(Json.map()
                                    .set("path", "api/v1/inbound-domains")
                                    .set("body", Json.map()
                                            .set("domain", domainToRegister)
                                    )
                            );
                            appLogger.info(String.format("Domain [%s] registered as Inbound Domain on SparkPost", domainToRegister));
                            logger.info(String.format("Inbound Domain [%s] registered", domainToRegister));
                        }

                        // check relay webhooks
                        final List<String> noRegisteredRelayWebhooks = new ArrayList<>();
                        response = httpService().defaultGetRequest(Json.map().set("path", "api/v1/relay-webhooks"));
                        List<Json> registeredRelayWebhooks = response.jsons("results");
                        if(registeredRelayWebhooks != null && !registeredRelayWebhooks.isEmpty()) {
                            for (String domain : iDomains) {
                                boolean registerRelayWebhook = true;
                                for (Json registeredRelayWebhook : registeredRelayWebhooks) {
                                    Json match = registeredRelayWebhook.json("match");
                                    if(match != null && domain.equalsIgnoreCase(match.string("domain"))){
                                        registerRelayWebhook = false;
                                        logger.info(String.format("Relay webhook [%s] already registered - id [%s]", domain, registeredRelayWebhook.string("id")));
                                        break;
                                    }
                                }
                                if(registerRelayWebhook){
                                    noRegisteredRelayWebhooks.add(domain);
                                }
                            }
                        } else {
                            noRegisteredRelayWebhooks.addAll(iDomains);
                        }

                        // register relay webhooks
                        for (String relayWebhookToRegister : noRegisteredRelayWebhooks) {
                            logger.info(String.format("Registering relay webhook [%s]", relayWebhookToRegister));
                            response = httpService().defaultPostRequest(Json.map()
                                    .set("path", "api/v1/relay-webhooks")
                                    .set("body", Json.map()
                                            .set("name", "Replies Webhook")
                                            .set("target", properties().getWebServicesUri()+"/inbound")
                                            .set("match", Json.map()
                                                    .set("protocol", "SMTP")
                                                    .set("domain", relayWebhookToRegister)
                                            )
                                    )
                            );
                            appLogger.info(String.format("Domain [%s] registered as Relay Webhook on SparkPost", relayWebhookToRegister));
                            logger.info(String.format("Relay Webhook [%s] registered for domain [%s]",
                                    response.json("results") != null ? response.json("results").string("id") : "-", relayWebhookToRegister));
                        }
                    }
                }
            }
        } catch (EndpointException rex){
            logger.info(String.format("Exception when try to check SparkPost domain configuration [%s]: %s", rex.getMessage(), rex.getJson(false)));
            throw rex;
        } catch (Exception ex){
            logger.info(String.format("Exception when try to check SparkPost domain configuration [%s]", ex.getMessage()));
            throw EndpointException.permanent(ErrorCode.CLIENT, String.format("Exception when try to check SparkPost configuration [%s]", ex.getMessage()), ex);
        }
    }

    @EndpointFunction(name = "_removeInboundDomain")
    public void removeInboundDomain(FunctionRequest request){
        final Json jsonBody = request.getJsonParams();
        if(jsonBody == null || jsonBody.isEmpty()){
            throw EndpointException.permanent(ErrorCode.ARGUMENT, "Domain is empty");
        }
        String domain = jsonBody.string("domain");
        if(StringUtils.isBlank(domain)){
            throw EndpointException.permanent(ErrorCode.ARGUMENT, String.format("Empty domain [%s]", domain));
        }
        domain = domain.trim().toLowerCase();

        // relay webhooks
        logger.info(String.format("Removing relay webhooks with domain [%s]", domain));
        Json response = httpService().defaultGetRequest(Json.map().set("path", "api/v1/relay-webhooks"), request.getFunctionId());
        List<Json> registeredRelayWebhooks = response.jsons("results");
        if(registeredRelayWebhooks != null && !registeredRelayWebhooks.isEmpty()) {
            for (Json registeredRelayWebhook : registeredRelayWebhooks) {
                final String id = registeredRelayWebhook.string("id");
                if(StringUtils.isNotBlank(id)) {
                    Json match = registeredRelayWebhook.json("match");
                    if (match != null && domain.equalsIgnoreCase(match.string("domain"))) {
                        httpService().defaultDeleteRequest(Json.map().set("path", String.format("/api/v1/relay-webhooks/%s", id)));
                        appLogger.info(String.format("Relay webhook [%s] deleted", id));
                    }
                }
            }
        }

        // inbound endpoints
        logger.info(String.format("Removing inbound domains for [%s]", domain));
        response = httpService().defaultGetRequest(Json.map().set("path", "api/v1/inbound-domains"), request.getFunctionId());
        final List<Json> registeredDomains = response.jsons("results");
        if(registeredDomains != null && !registeredDomains.isEmpty()) {
            for (Json registeredDomain : registeredDomains) {
                if(domain.equalsIgnoreCase(registeredDomain.string("domain"))){
                    httpService().defaultDeleteRequest(Json.map().set("path", String.format("/api/v1/inbound-domains/%s", domain)));
                    logger.info(String.format("Inbound domain [%s] deleted", domain));
                }
            }
        }
    }

    private Json completeTransmissionFrom(Json body, String messageId) {
        final Json content = body.json("content");
        if(content != null && !content.contains("push")){
            // inline content email, complete 'from' with endpoint information

            boolean modified = false;
            Object oFrom = content.object("from");
            Json from;
            if(oFrom instanceof Json) {
                from = (Json) oFrom;
            } else {
                final String fromAddress = oFrom != null ? oFrom.toString() : null;
                from = Json.map().set("email", fromAddress);
                modified = true;
            }

            if(StringUtils.isBlank(from.string("name")) && StringUtils.isNotBlank(senderName)){
                from.set("name", senderName);
                modified = true;
            }
            String email = from.string("email");
            if(StringUtils.isBlank(email) || !email.contains("@")){
                // empty or invalid email
                if(StringUtils.isNotBlank(domain)) {
                    // generate the email from domain
                    if (StringUtils.isBlank(messageId)) {
                        email = generateSenderAddress();
                    } else {
                        email = generateSenderAddress(messageId);
                    }
                    modified = true;
                    content.set("reply_to", email); // force Reply-To with messageId
                }
            } else {
                // include message id if does not contain any code
                String ac = email.substring(0, email.indexOf("@"));
                if((StringUtils.isBlank(ac) || !ac.contains("+")) && StringUtils.isNotBlank(messageId)) {
                    email = generateSenderAddress(ac, messageId, email.substring(email.indexOf("@")+1));
                    modified = true;
                    content.set("reply_to", email); // force Reply-To with messageId
                }
            }

            if(modified) {
                from.set("email", email);
                content.set("from", from);
                body.set("content", content);
            }

            if(email.contains("+") && email.contains("@")){
                body.set("__message_id", email.substring(email.indexOf("+")+1, email.indexOf("@"))); // temporal field
            }
        }
        return body;
    }

    private Json completeTransmissionFiles(Json body) {
        final Json content = body.json("content");
        if(content != null){
            completeListFiles(content, "attachments");
            completeListFiles(content, "inline_images");
            body.set("content", content);
        }
        return body;
    }

    private void completeListFiles(Json content, String property) {
        if(content.contains(property)){
            boolean modified = false;
            final List<Json> files = content.jsons(property);
            for (Json file : files) {
                if(file.contains("fileId") && !file.contains("data")){
                    file.set("data", files().download(file.string("fileId"), true));
                    modified = true;
                }
                file.remove("fileId");
            }
            if(modified) {
                content.set(property, files);
            }
        }
    }

    private String generateSenderAddress(){
        return generateSenderAddress(null);
    }

    private String generateSenderAddress(String messageId){
        return generateSenderAddress(sender, messageId, domain);
    }

    private String generateSenderAddress(String account, String messageId, String domain){
        account = StringUtils.isNotBlank(account) ? account : "info";
        if(StringUtils.isNotBlank(messageId)){
            return String.format("%s+%s@%s", account, messageId, domain);
        } else {
            return String.format("%s@%s", account, domain);
        }
    }

    private String generateMessageId(){
        return Strings.randomAlphanumeric(8).toLowerCase();
    }

}

