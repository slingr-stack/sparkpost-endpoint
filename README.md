---
title: SparkPost endpoint
keywords: 
last_updated: April 20, 2017
tags: []
summary: "Detailed description of the API of the SparkPost endpoint."
---

## Overview

The SparkPost endpoint allows to send emails and also get events from them like received, opened,
o answered.

Some of the features are:

- Shortcuts for the REST API
- Automatic configuration of inbound domain and relay webhooks
- Helpers to send emails and receive responses
- Helpers to convert HTML to plain text and the other way around
- Helpers to extract response from email thread

Apart from helpers you will see that in order to use the REST API of SparkPost you will be making
regular HTTP request to the REST API. For example:

```js
var res = app.endpoints.sparkpost.get('api/v1/relay-webhooks');
```

In most cases the provided helpers and events are enough for most use cases, but if you need to
use the SparkPost REST API you should go to their [documentation](https://developers.sparkpost.com/api/).

## Quick start

You can send an email like this:

```js
app.endpoints.sparkpost.sendEmail({
  "content": {
    "subject": "Email from SparkPost",
    "text": "Test email sent with SparkPost."
  },
  "recipients": [{ "address": emailAddress }]
});
```

## Configuration

First you will need to setup an account in SparkPost. Then you will be able to configure the endpoint,
where you will need to:

- Create an API key
- Setup sending domains
- Setup inbound domains
- Setup webhooks

See each setting to get more information about how to do that.

### API key

You will need to generate an API key in [Account > API Keys](https://app.sparkpost.com/account/credentials).
Once you generate it copy the API key to this field.

The API key needs to have at least the following permissions to make all features on this endpoint
work well:

- `Account: Read`
- `Inbound Domains: Read/Write`
- `Relay Webhooks: Read-only`
- `Relay Webhooks: Read/Write`
- `Transmissions: Read-only`
- `Transmissions: Read/Write`

You will need to add other permissions if you use more things in SparkPost API.

### Default sender name

This is the default sender name to use in outgoing emails. You can override this when sending an email.

### Default sender email

This is the default sender email to use in outgoing emails. You can override this when sending an email.

Keep in mind that you need to configure a sending domain in SparkPost and your email address should
be under one of these registered domains. You can do it [here](https://app.sparkpost.com/account/sending-domains).

### Inbound domains

If you need to listen for email's responses or just to emails sent to a specific address, you will
need to configure an inbound domain. You can specify many inbound domains separating them with commas.

SparkPost doesn't provide a UI for it, so the endpoint will do it for you once you deploy the
endpoint. However you still need to setup your DNS with the MX records. See 
[SparkPost docs](https://www.sparkpost.com/docs/tech-resources/enabling-inbound-email/#add-mx-records) 
for more information about how to do it.

If you change this setting and you don't want to use an inbound domain any longer, you can use the
function `removeInboundDomain()` in the endpoint (or you can use the REST API directly). You won't 
be able to do it from SparkPost UI.

### Inbound domain URL

This is the URI to register on your SparkPost account when an Inbound Domain is created.

### Webhook URL

This is the URL you will need to copy to the webhook configured in SparkPost. This has to be configured
[here](https://app.sparkpost.com/account/webhooks) in SparkPost. When creating the webhook specify 
`Basic Auth` as the authentication mechanism and you will need to set a username and password (see
settings below).

Regarding events sent to webhooks, by default all will be sent, but you can change that if you want.

### Webhook username

This is the username configured in the webhook in SparkPost for authentication. This will prevent
other people to send requests to this URL.

### Webhook password

This is the password configured in the webhook in SparkPost for authentication. This will prevent
other people to send requests to this URL.

## Javascript API

The Javascript API provides direct access to the SparkPost API so you can make regular HTTP
request. You should check the [SparkPost API docs](https://developers.sparkpost.com/api/) to 
see what's available.

Additionally the endpoint provides some helpers to send emails and process responses and events
as well as some utilities to convert HTML to text and the other way around.

### HTTP requests

You can make `GET`, `POST`, `PUT`, and `DELETE` request to the 
[SparkPost API](https://developers.sparkpost.com/api/) like this:

```js
var account = app.endpoints.sparkpost.get('api/v1/account');
var inboundDomain = app.endpoints.sparkpost.post('api/v1/inbound-domains', {domain: 'domain'});
```

Please take a look at the documentation of the [HTTP endpoint]({{site.baseurl}}/endpoints_http.html#javascript-api)
for more information.

### Send email

```js
var res = app.endpoints.sparkpost.sendEmail(email, callbackData, callbacks);
```

As sending emails is probably the main use case you will have for the SparkPost we have added
some helpers to make this easier than just using the REST API directly.

Most of the format for sending emails and the response is described in the 
[Transmissions](https://developers.sparkpost.com/api/transmissions.html) section of the SparkPost
API docs. If there are any difference we will explicitly explain that below.

#### Send a simple email

To send a simple email you can do this:

```js
var res = app.endpoints.sparkpost.sendEmail({
  "content": {
    "subject": "Email from SparkPost",
    "text": "Test email sent with SparkPost."
  },
  "recipients": [{ "address": emailAddress }]
});
log('send email response: '+JSON.stringify(res));
```

#### Listen for responses

It is possible to listen for responses to emails:

```js
var res = app.endpoints.sparkpost.sendEmail(
  {
    "content": {
      "subject": "Email from SparkPost",
      "text": "Test email sent with SparkPost."
    },
    "recipients": [{ "address": action.field('emailTo').val() }]
  },
  {
    record: record
  },
  {
    responseArrived: function(event, callbackData) {
      sys.logs.info('*** RESPONSE ARRIVED FOR RECORD ['+callbackData.record.label()+']: '+JSON.stringify(event.data));
    }
  }
);
sys.logs.info('*** EMAIL SENT WITH SPARKPOST: '+JSON.stringify(res));
```

Keep in mind that SparkPost will keep a reference to the sent email for 15 days. If the email is
answered after that period of time the callback won't be executed and the response will be received
as an `Email Arrived` event.

#### Handling attachments

To make it easier to send attachments we made a few changes to the SparkPost API so you can just pass
the file ID like this:

```js
var res = app.endpoints.sparkpost.sendEmail({
  "content": {
    "subject": "Email from SparkPost",
    "text": "Test email sent with SparkPost."
  },
  "attachments": [
    {
      "type": "text/plain",
      "name": "text.txt",
      "data": "ewogICAgImxhYmV.....90ZSB0aG"  // Base 64 content
    },
    {
      "type": "text/plain",
      "name": "text.txt",
      "data": record.field("file").content() // content of a file
    },
    {
      "fileId": record.field("file").id(), // file via ID
      "name": record.field("file").name(),
      "type": record.field("file").contentType()
    }
  ],
  "recipients": [{ "address": emailAddress }]
});
log('send email response: '+JSON.stringify(res));
```

As you can see there are different options. The most efficient way to do it is by using the
file ID.

### Extract email response

```js
var response = app.endpoints.sparkpost.extractTextResponse(text);
var response = app.endpoints.sparkpost.extractHtmlResponse(html);
```

These methods are useful to extract the response of the user and discard the other parts of the
thread. For example, when a user replies to an email you will get something like this in the
field `event.data.body.msys.relay_message.content.text`:

```
ABC\n\n def *GHIIIII*\n\nOn Thu, Apr 27, 2017 at 5:58 PM Test SparkPost Endpoint <\nsender+dzoo85nw@abc.net> wrote:\n\n> simple message\n >\n >
```

There you can see you don't only have the response but also the original email. If you jsut want to
process the response you can extract it like this:

```js
var response = app.endpoints.sparkpost.extractTextResponse(event.data.body.msys.relay_message.content.text);
sys.logs.info(response);
```

For the above sample it will log `ABC def *GHIIIII*`.

If you want to extract the HTML response use the method `extractHtmlResponse(html)` instead. For example:

```js
var response = app.endpoints.sparkpost.extractTextResponse(event.data.body.msys.relay_message.content.html);
sys.logs.info(response);
```

### Convert between HTML and plain text

```js
var text = app.endpoints.sparkpost.convertToText(html);
var html = app.endpoints.sparkpost.convertToHtml(text);
```

These methods allow to convert from HTML to plain text and the other way around.

### Inbound domains configuration

```js
app.endpoints.sparkpost.configureInboundDomains();
app.endpoints.sparkpost.removeInboundDomain(domain);
```

This allows to configure inbound domains. The method `configureInboundDomains()` will be
automatically called when the endpoint is started and will configure inbound domains set
in the endpoint's settings if they haven't been configured yet. So in most cases you don't 
need to use it.

If you change inbound domains, old ones won't be deleted automatially from SparkPost. If you
want to do it, you need to use the method `removeInboundDomain(domain)`:

```js
app.endpoints.sparkpost.removeInboundDomain('oldinbound.mycompany.com');
```

## Events

These are the events sent in webhooks. The events you will get will depends on the events configured
in SparkPost to be sent for that webhook.

### Service Event

This are the events enabled in the webhook like email bounce, delivery, click, open, etc.

You can get sample events to see their format by running this script in the console:

```js
var res = app.endpoints.sparkpost.get('/api/v1/webhooks/events/samples');
log(JSON.stringify(res));
```

You can get a sample of an specific event:

```js
var res = app.endpoints.sparkpost.get({
  path: '/api/v1/webhooks/events/samples',
  params: {
    events: 'bounce'
  }
});
log(JSON.stringify(res));
```

#### Service events used on callbacks

It is possible to listen for service events related to an email send through `sendEmail` function with a callback:

```js
var res = app.endpoints.sparkpost.sendEmail(
  {
    "content": {
      "subject": "Email from SparkPost",
      "text": "Test email sent with SparkPost."
    },
    "recipients": [{ "address": action.field('emailTo').val() }]
  },
  {
    record: record
  },
  {
    responseArrived: function(event, callbackData) {
      sys.logs.info('*** RESPONSE ARRIVED FOR RECORD ['+callbackData.record.label()+']: '+JSON.stringify(event.data));
    },
    serviceEvent: function(event, callbackData) {
      sys.logs.info('*** SERVICE EVENT ARRIVED FOR RECORD ['+callbackData.record.label()+']: '+JSON.stringify(event.data));
    },
  }
);
sys.logs.info('*** EMAIL SENT WITH SPARKPOST: '+JSON.stringify(res));
```

Keep in mind that SparkPost will keep a reference to the sent email for 15 days. If a service event is
received after that period of time the callback won't be executed and the event is received like any
other service events.

### Email Arrived

This event is triggered when an email is processed through the inbound domain. Keep in mind that
emails processed through the event `Response Arrived` won't be included here.

You can find information about the data sent in the event [here](https://developers.sparkpost.com/api/relay-webhooks.html).

### Response Arrived

This is when an email is processed through the inbound domain and it is a reply to an email sent
through `sendEmail()` method. In most cases you will process the email through a callback.

You can find information about the data sent in the event [here](https://developers.sparkpost.com/api/relay-webhooks.html).

## About SLINGR

SLINGR is a low-code rapid application development platform that accelerates development, with robust architecture for integrations and executing custom workflows and automation.

[More info about SLINGR](https://slingr.io)

## License

This endpoint is licensed under the Apache License 2.0. See the `LICENSE` file for more details.




