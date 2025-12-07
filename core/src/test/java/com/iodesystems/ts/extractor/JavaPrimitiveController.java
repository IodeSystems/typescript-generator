package com.iodesystems.ts.extractor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/java")
public class JavaPrimitiveController {

    @PostMapping("/booleanPrimitive")
    public boolean booleanPrimitive(@RequestBody boolean req) { return req; }

    @PostMapping("/booleanWrapper")
    public Boolean booleanWrapper(@RequestBody Boolean req) { return req; }

    @PostMapping("/bytePrimitive")
    public byte bytePrimitive(@RequestBody byte req) { return req; }

    @PostMapping("/byteWrapper")
    public Byte byteWrapper(@RequestBody Byte req) { return req; }

    @PostMapping("/shortPrimitive")
    public short shortPrimitive(@RequestBody short req) { return req; }

    @PostMapping("/shortWrapper")
    public Short shortWrapper(@RequestBody Short req) { return req; }

    @PostMapping("/intPrimitive")
    public int intPrimitive(@RequestBody int req) { return req; }

    @PostMapping("/intWrapper")
    public Integer intWrapper(@RequestBody Integer req) { return req; }

    @PostMapping("/longPrimitive")
    public long longPrimitive(@RequestBody long req) { return req; }

    @PostMapping("/longWrapper")
    public Long longWrapper(@RequestBody Long req) { return req; }

    @PostMapping("/floatPrimitive")
    public float floatPrimitive(@RequestBody float req) { return req; }

    @PostMapping("/floatWrapper")
    public Float floatWrapper(@RequestBody Float req) { return req; }

    @PostMapping("/doublePrimitive")
    public double doublePrimitive(@RequestBody double req) { return req; }

    @PostMapping("/doubleWrapper")
    public Double doubleWrapper(@RequestBody Double req) { return req; }

    @PostMapping("/charPrimitive")
    public char charPrimitive(@RequestBody char req) { return req; }

    @PostMapping("/charWrapper")
    public Character charWrapper(@RequestBody Character req) { return req; }

    @PostMapping("/string")
    public String string(@RequestBody String req) { return req; }

    // Collections
    @PostMapping("/listInteger")
    public List<Integer> listInteger(@RequestBody List<Integer> req) { return req; }

    @PostMapping("/setString")
    public Set<String> setString(@RequestBody Set<String> req) { return req; }

    @PostMapping("/mapStringDouble")
    public Map<String, Double> mapStringDouble(@RequestBody Map<String, Double> req) { return req; }
}
