package com.elderbyte.commons.data.contiunation;

import com.elderbyte.commons.utils.Utf8Base64;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

public class JsonContinuationTokenBuilder {

    /***************************************************************************
     *                                                                         *
     * Fields                                                                  *
     *                                                                         *
     **************************************************************************/

    private final ObjectMapper mapper;

    /***************************************************************************
     *                                                                         *
     * Constructors                                                            *
     *                                                                         *
     **************************************************************************/

    public JsonContinuationTokenBuilder(ObjectMapper mapper){
        this.mapper = mapper;
    }

    /***************************************************************************
     *                                                                         *
     * Public API                                                              *
     *                                                                         *
     **************************************************************************/

    /**
     * Builds a continuation token with the given payload
     */
    public ContinuationToken buildJsonToken(Object data){
        try {
            String json = mapper.writeValueAsString(data);
            String base64 = Utf8Base64.encodeUtf8(json);
            return ContinuationToken.from(base64);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could encode object as json string!", e);
        }
    }

    public <T> Optional<T> asJson(ContinuationToken token, Class<T> clazz){
        return decodeBase64(token)
                .map(json -> {
                    try {
                        return mapper.readValue(json, clazz);
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not parse token as JSON", e);
                    }
                });
    }

    /***************************************************************************
     *                                                                         *
     * Private methods                                                         *
     *                                                                         *
     **************************************************************************/

    public ObjectMapper getMapper(){
        return mapper;
    }

    private Optional<String> decodeBase64(ContinuationToken token){
        return token.getTokenIfNotEmpty()
                .map(value -> Utf8Base64.decodeUtf8(value));
    }


}
