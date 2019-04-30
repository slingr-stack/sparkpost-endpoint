
/////////////////////
// Public API
/////////////////////

endpoint.sendEmail = function(options, callbackData, callbacks) {
    options = options || {};
    return endpoint._sendEmail(options, callbackData, callbacks);
};

endpoint.configureInboundDomains = function() {
    return endpoint._configureInboundDomains({});
};

endpoint.removeInboundDomain = function(domain) {
    return endpoint._removeInboundDomain({domain:domain});
};

endpoint.convertToText = function(value) {
    return endpoint._convertToText({value:value}).result;
};

endpoint.convertToHtml = function(value) {
    return endpoint._convertToHtml({value:value}).result
};

endpoint.extractTextResponse = function(value) {
    return endpoint._extractTextResponse({value:value}).result;
};

endpoint.extractHtmlResponse = function(value) {
    return endpoint._extractHtmlResponse({value:value}).result
};

///////////////////////////////////
// Public API - Generic Functions
/////////////////////////////////

endpoint.get = function(url) {
    var options = checkHttpOptions(url, {});
    return endpoint._get(options)
};

endpoint.post = function(url, options) {
    options = checkHttpOptions(url, options);
    return endpoint._post(options)
};

endpoint.put = function(url, options) {
    options = checkHttpOptions(url, options);
    return endpoint._put(options)
};

endpoint.delete = function(url) {
    var options = checkHttpOptions(url, {});
    return endpoint._delete(options);
};

/////////////////////////////
//  Private helpers
/////////////////////////////

var checkHttpOptions = function (url, options) {
    options = options || {};
    if (!!url) {
        if (isObject(url)) {
            // take the 'url' parameter as the options
            options = url || {};
        } else {
            if (!!options.path || !!options.params || !!options.body) {
                // options contains the http package format
                options.path = url;
            } else {
                // create html package
                options = {
                    path: url,
                    body: options
                }
            }
        }
    }
    return options;
};

var isObject = function (obj) {
    return !!obj && stringType(obj) === '[object Object]'
};

var stringType = Function.prototype.call.bind(Object.prototype.toString);
