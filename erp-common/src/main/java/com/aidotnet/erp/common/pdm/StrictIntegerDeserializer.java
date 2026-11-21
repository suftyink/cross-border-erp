package com.aidotnet.erp.common.pdm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** 数量不得把 1.5 静默截断成 1。仅用于明确要求整数的新商品字段。 */
public class StrictIntegerDeserializer extends StdDeserializer<Integer> {
    public StrictIntegerDeserializer() { super(Integer.class); }
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
