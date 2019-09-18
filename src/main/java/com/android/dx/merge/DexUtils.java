package com.android.dx.merge;

import java.io.*;
import java.util.*;

import com.android.dex.Annotation;
import com.android.dex.ClassData;
import com.android.dex.ClassDef;
import com.android.dex.Dex;
import com.android.dex.EncodedValueReader;
import com.android.dex.FieldId;
import com.android.dex.MethodId;
import com.android.dex.ProtoId;
import com.android.dx.merge.DexMerger.Injection;
import com.android.dx.merge.DexMerger.SwapMethod;

public final class DexUtils {

    public static final String ANNOTATION_BLADEINJECT = "Lorg/blade/hooklib/BladeInject;";
    public static final String ANNOTATION_BLADESWAP = "Lorg/blade/hooklib/BladeSwap;";

    static public String getStringOfProtoId(Dex dex, ProtoId proto) {
        return dex.readTypeList(proto.getParametersOffset()) + dex.typeNames().get(proto.getReturnTypeIndex());
    }

    static public int getIndexOfSignature(Dex dex, String signature) {
        int idx = 0;
        for (ProtoId proto : dex.protoIds()) {
            if (getStringOfProtoId(dex, proto).equals(signature))
                return idx;
            idx++;
        }
        return -1;
    }

    static public int getIndexOfString(Dex dex, String string) {
        int idx = 0;
        for (String str : dex.strings()) {
            if (str.equals(string))
                return idx;
            idx++;
        }
        return -1;
    }

    static public ClassDef findClass(Dex dex, String Name) {
        for (ClassDef classdef : dex.classDefs()) {
            int typeIdx = dex.typeIds().get(classdef.getTypeIndex());
            String ClassName = dex.strings().get(typeIdx);

            if (ClassName.equals(Name))
                return classdef;
        }

        return null;
    }

    static public ClassData.Method findMethod(Dex dex, ClassDef classdef, int nameIdx, int protoIdx) {
        for (ClassData.Method method : dex.readClassData(classdef).allMethods()) {
            MethodId methodId = dex.methodIds().get(method.getMethodIndex());
            if (methodId.getNameIndex() == nameIdx && methodId.getProtoIndex() == protoIdx) {
                return method;
            }
        }

        return null;
    }

    public static boolean fieldExists(Dex in, ClassData.Field[] fields, Dex shadowDex, ClassData.Field f) {
        List<String> strings = shadowDex.strings();
        List<String> _strings = in.strings();
        List<FieldId> _fieldIds = in.fieldIds();
        List<Integer> _typeIds = in.typeIds();

        FieldId fid = shadowDex.fieldIds().get(f.getFieldIndex());
        String cn = strings.get(shadowDex.typeIds().get(fid.getDeclaringClassIndex()));
        String tn = strings.get(fid.getNameIndex());

        for (ClassData.Field _f : fields) {
            FieldId _fid = _fieldIds.get(_f.getFieldIndex());

            if (_strings.get(_fid.getNameIndex()).equals(tn)
                    && _strings.get(_typeIds.get(_fid.getDeclaringClassIndex())).equals(cn))
                return true;
        }

        return false;
    }

    public static boolean methodExists(Dex in, ClassData.Method[] methods, Dex shadowDex, ClassData.Method m) {
        List<String> strings = shadowDex.strings();
        List<String> _strings = in.strings();
        List<MethodId> _methodIds = in.methodIds();
        List<ProtoId> _protoIds = in.protoIds();

        MethodId mid = shadowDex.methodIds().get(m.getMethodIndex());
        String proto = shadowDex.protoIds().get(mid.getProtoIndex()).toString();
        String mn = strings.get(mid.getNameIndex());

        for (ClassData.Method _m : methods) {
            MethodId _mid = _methodIds.get(_m.getMethodIndex());

            if (_strings.get(_mid.getNameIndex()).equals(mn)
                    && _protoIds.get(_mid.getProtoIndex()).toString().equals(proto))
                return true;
        }
        return false;
    }

    public static final String[] AnnotaionVisibilityNames = {"Build", "Runtime", "System"};

    public static Map<String, Map<String, String>> readAnnotationSet(Dex dex, Dex.Section annoSet) {
        List<String> strings = dex.strings();
        List<String> typeNames = dex.typeNames();
        Map<String, Map<String, String>> result = new HashMap<>();

        int numAnno = annoSet.readInt();
        for (int i = 0 ; i < numAnno; ++i) {
            Dex.Section annoItems = dex.open(annoSet.readInt());
            Annotation anno = annoItems.readAnnotation();

            Map<String, String> nameValueSet = new HashMap<>();
            result.put(typeNames.get(anno.getTypeIndex()), nameValueSet);

            EncodedValueReader reader = anno.getReader();
            int fieldCount = reader.readAnnotation();
            for (int j = 0 ; j < fieldCount; ++j) {
                String name = strings.get(reader.readAnnotationName());
                String value = "";
                if (reader.peek() == EncodedValueReader.ENCODED_STRING)
                    value = strings.get(reader.readString());
                nameValueSet.put(name, value);
            }
        }

        return result;
    }

    public static List<Injection> extractInjections(Dex dex) {
        List<String> strings = dex.strings();
        List<String> typeNames = dex.typeNames();
        List<ProtoId> protoIds = dex.protoIds();
        List<MethodId> methodIds = dex.methodIds();

        List<Injection> injections = new ArrayList<>();

        for (ClassDef classDef : dex.classDefs()) {

            if (classDef.getAnnotationsOffset() != 0) {

                Dex.Section annoDir = dex.open(classDef.getAnnotationsOffset());

                // Read class annotation
                Dex.Section annoSet = dex.open(annoDir.readInt());
                Map<String, Map<String, String>> classAnnos = readAnnotationSet(dex, annoSet);
                
                if (classAnnos.containsKey(ANNOTATION_BLADEINJECT) && classAnnos.get(ANNOTATION_BLADEINJECT).containsKey("name")) {
                    Injection injection = new Injection();
                    injection.shadowDex = dex;
                    injection.shadowClassName = typeNames.get(classDef.getTypeIndex());
                    injection.targetClassName = "L" + classAnnos.get(ANNOTATION_BLADEINJECT).get("name").replace('.', '/') + ";";

                    // Read method annotation
                    int annotatedFieldsSize = annoDir.readInt();
                    int annotatedMethodsSize = annoDir.readInt();
                    annoDir.readInt(); // Skip annotated parameters size

                    // Skip fields
                    for (int i = 0 ; i < annotatedFieldsSize; ++i) {
                        annoDir.readInt();
                        annoDir.readInt();
                    }

                    // Read method annotation
                    for (int i = 0 ; i < annotatedMethodsSize; ++i) {
                        int methodIdx = annoDir.readInt();
                        int annotationsOff = annoDir.readInt();

                        annoSet = dex.open(annotationsOff);
                        Map<String, Map<String, String>> methodAnnos = readAnnotationSet(dex, annoSet);

                        if (methodAnnos.containsKey(ANNOTATION_BLADESWAP) && methodAnnos.get(ANNOTATION_BLADESWAP).containsKey("from")) {
                            MethodId methodId = methodIds.get(methodIdx);
                            String methodSignature = getStringOfProtoId(dex, protoIds.get(methodId.getProtoIndex()));

                            SwapMethod sm = new SwapMethod(methodAnnos.get(ANNOTATION_BLADESWAP).get("from") + methodSignature, 
                                strings.get(methodId.getNameIndex()) + methodSignature);
                            injection.swaps.add(sm);
                        }
                    }

                    // Ignore parameter annotation

                    injections.add(injection);
                }
            }
        
        }

        return injections;
    }
}
