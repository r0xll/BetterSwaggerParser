package io.github.r0xll.betterswagger.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParamValuesTest {

    private static Endpoint get(String path, String... pathParams) {
        List<Param> params = new java.util.ArrayList<>();
        for (String name : pathParams) {
            params.add(new Param(name, Param.Location.PATH, true, "0"));
        }
        return new Endpoint("GET", path, "", params, List.of(), null, null);
    }

    private static String value(Endpoint e, String name) {
        return e.parameters().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().exampleValue();
    }

    @Test
    void parsesGlobalScopedAndComments() {
        ParamValues v = ParamValues.parse("""
                # real IDs
                petId=1337
                /users/{id} id=42
                  /orders/{id}   id = 9001
                token=a=b=c
                not a value line
                """);
        assertEquals("1337", v.global().get("petId"));
        assertEquals("a=b=c", v.global().get("token"), "only the first = separates name and value");
        assertTrue(v.covers("/users/{id}", "id"));
        assertTrue(v.covers("/orders/{id}", "id"));
        assertFalse(v.covers("/pets/{id}", "id"));
    }

    @Test
    void scopedValueWinsOverGlobalAndLaterLinesWin() {
        ParamValues v = ParamValues.parse("id=1\n/users/{id} id=42\nid=2\n");
        assertEquals("42", value(v.applyTo(get("/users/{id}", "id")), "id"));
        assertEquals("2", value(v.applyTo(get("/orders/{id}", "id")), "id"));
    }

    @Test
    void missingLinesAreScopedOnlyForSharedNames() {
        List<Endpoint> endpoints = List.of(
                get("/users/{id}", "id"), get("/orders/{id}", "id"), get("/pets/{petId}", "petId"));

        assertEquals(List.of("/users/{id} id=0", "/orders/{id} id=0", "petId=0"),
                ParamValues.parse("").missingPathParameterLines(endpoints));

        assertEquals(List.of("/orders/{id} id=0"),
                ParamValues.parse("/users/{id} id=42\npetId=7").missingPathParameterLines(endpoints),
                "values already set are not repeated");
    }
}
