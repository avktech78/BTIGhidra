package binary_type_inference;

import ctypes.Ctypes.Alias;
import ctypes.Ctypes.CTypeMapping;
import ctypes.Ctypes.Field;
import ctypes.Ctypes.Function;
import ctypes.Ctypes.Parameter;
import ctypes.Ctypes.Pointer;
import ctypes.Ctypes.Primitive;
import ctypes.Ctypes.Structure;
import ctypes.Ctypes.Tid;
import ctypes.Ctypes.TidToNodeIndex;
import generic.stl.Pair;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.FunctionDefinitionDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.ParameterDefinition;
import ghidra.program.model.data.ParameterDefinitionImpl;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TypeLibrary {
  private final CTypeMapping mapping;
  private final DataType unknownType;
  private final Map<String, DataType> type_constants;

  private final Map<Integer, DataType> node_index_to_type_memoization;
  private final DataTypeManager dtm;

  public static class Types {
    private final Map<Tid, DataType> mapping;

    public Types(Map<Tid, DataType> mapping) {
      this.mapping = mapping;
    }

    public Optional<DataType> getDataTypeForTid(Tid ti) {
      return Optional.ofNullable(mapping.get(ti));
    }
  }

  private TypeLibrary(
      CTypeMapping mapping,
      Map<String, DataType> type_constants,
      DataType unknownType,
      DataTypeManager dtm) {
    this.mapping = mapping;
    this.type_constants = type_constants;
    this.unknownType = unknownType;
    this.node_index_to_type_memoization = new HashMap<>();
    this.dtm = dtm;
  }

  // TODO(ian): this assumes we never get an alias to ourselves
  private DataType build_alias(Alias to) {
    return this.build_node_type(to.getToType());
  }

  private ParameterDefinition BuildParamDefForParam(
      DataType curr_type, int node_index, int param_idx, int param_node) {
    var param_ty = this.rec_build_node_type(param_node, node_index, curr_type);
    var param =
        new ParameterDefinitionImpl(
            "func_" + Integer.toString(node_index) + "param_" + Integer.toString(param_idx),
            param_ty,
            "autogenerated");
    return param;
  }

  private ParameterDefinition BuildDefaultParameter(int node_index, int param_idx) {
    var param =
        new ParameterDefinitionImpl(
            "func_" + Integer.toString(node_index) + "param_" + Integer.toString(param_idx),
            this.unknownType,
            "autogenerated");
    return param;
  }

  private DataType build_function(int node_index, Function func) {
    FunctionDefinitionDataType res_type =
        new FunctionDefinitionDataType("func_type_for_" + Integer.toString(node_index));

    if (func.getHasReturn()) {
      var ret_ty = this.rec_build_node_type(func.getReturnType(), node_index, res_type);
      res_type.setReturnType(ret_ty);
    }

    var max_ind =
        func.getParametersList().stream()
            .map((Parameter p) -> p.getParameterIndex())
            .max(Integer::compare);

    if (!max_ind.isPresent()) {
      // no params.
      return res_type;
    }

    var param_map =
        func.getParametersList().stream()
            .collect(Collectors.toMap(Parameter::getParameterIndex, Parameter::getType));

    var params =
        IntStream.range(0, max_ind.get() + 1)
            .mapToObj(
                (int param_idx) -> {
                  if (param_map.containsKey(param_idx)) {
                    return this.BuildParamDefForParam(
                        res_type, node_index, param_idx, param_map.get(param_idx));
                  } else {
                    return this.BuildDefaultParameter(node_index, param_idx);
                  }
                })
            .toArray(ParameterDefinition[]::new);

    res_type.setArguments(params);

    return res_type;
  }

  private DataType build_primitive(Primitive prim) {
    // TODO(ian): figure out size based on use
    if (prim.getTypeConstant().equals(OutputBuilder.SPECIAL_WEAK_INTEGER)) {
      return IntegerDataType.dataType;
    }

    return this.type_constants.getOrDefault(prim.getTypeConstant(), this.unknownType);
  }

  class ModifiablePointer extends PointerDataType {

    public ModifiablePointer(DataTypeManager dtm) {
      super(dtm);
    }

    public void setReferencedDataType(DataType ty) {
      this.referencedDataType = ty;
    }
  }

  private DataType build_pointer(Pointer ptr, int prev_index) {
    // TODO(ian): evaluate if this call is safe, can pointers loop?
    var pointed_to = this.build_node_type(ptr.getToType());
    return new PointerDataType(pointed_to);
  }

  private static class InsertedField {
    public int bit_size;
    public int byte_offset;

    public InsertedField(int bit_size, int byte_offset) {
      this.bit_size = bit_size;
      this.byte_offset = byte_offset;
    }

    public int get_byte_past_end() {
      return this.byte_offset + (this.bit_size / 8);
    }
  }

  private DataType build_structure(Structure struct, int node_index) {

    var new_fld_list = new ArrayList<Pair<InsertedField, DataType>>();

    var unsorted_flds = struct.getFieldsList();

    var flds =
        unsorted_flds.stream()
            .sorted((Field f1, Field f2) -> Integer.compare(f1.getByteOffset(), f2.getByteOffset()))
            .collect(Collectors.toList());

    var st = new StructureDataType("struct_for_node_" + Integer.toString(node_index), 0);

    for (var fld : flds) {
      var min_unoccupied = 0;
      if (!new_fld_list.isEmpty()) {
        var last_elem = new_fld_list.get(new_fld_list.size() - 1);
        min_unoccupied = last_elem.first.get_byte_past_end();
      }

      if (fld.getByteOffset() != min_unoccupied) {
        assert (fld.getByteOffset() > min_unoccupied);
        var diff = fld.getByteOffset() - min_unoccupied;
        var diff_in_bits = diff * 8;
        var ifld = new InsertedField(diff_in_bits, min_unoccupied);
        new_fld_list.add(new Pair<>(ifld, this.unknownType));
      }

      var fld_ty = this.rec_build_node_type(fld.getType(), node_index, st);
      new_fld_list.add(
          new Pair<>(new InsertedField(fld.getBitSize(), fld.getByteOffset()), fld_ty));
    }

    for (var to_add : new_fld_list) {
      st.add(
          to_add.second,
          to_add.first.bit_size / 8,
          "field_at_" + Integer.toString(to_add.first.byte_offset),
          "autogen");
    }

    return st;
  }

  private DataType no_memo_build_node(int node_index) {
    var target_node = this.mapping.getNodeTypesMap().get(node_index);
    Objects.requireNonNull(target_node);

    var type_case = target_node.getInnerTypeCase();

    switch (type_case) {
      case ALIAS:
        return this.build_alias(target_node.getAlias());
      case FUNCTION:
        return this.build_function(node_index, target_node.getFunction());
      case STRUCTURE:
        return this.build_structure(target_node.getStructure(), node_index);
      case POINTER:
        return this.build_pointer(target_node.getPointer(), node_index);
      case PRIMITIVE:
        return this.build_primitive(target_node.getPrimitive());
      case INNERTYPE_NOT_SET:
      default:
        return this.unknownType;
    }
  }

  // A recursive call to build node type. A builder should never call
  // build_node_type, or else risk an infinite loop
  private DataType rec_build_node_type(int node_index, int prev_index, DataType prev) {
    this.node_index_to_type_memoization.put(prev_index, prev);
    return this.build_node_type(node_index);
  }

  private DataType build_node_type(int node_index) {
    if (this.node_index_to_type_memoization.containsKey(node_index)) {
      return this.node_index_to_type_memoization.get(node_index);
    }

    var res = this.no_memo_build_node(node_index);
    this.node_index_to_type_memoization.put(node_index, res);
    return res;
  }

  private Optional<Pair<Tid, DataType>> get_type_of_tid(TidToNodeIndex type_var) {
    if (this.mapping.containsNodeTypes(type_var.getNodeIndex())) {
      return Optional.of(
          new Pair<Tid, DataType>(
              type_var.getTid(), this.build_node_type(type_var.getNodeIndex())));
    } else {
      return Optional.empty();
    }
  }

  public Types buildMapping() {
    // memoization map

    Map<Tid, DataType> tid_mapping_memo =
        this.mapping.getTypeVariableReprNodesList().stream()
            .map((TidToNodeIndex type_var) -> this.get_type_of_tid(type_var))
            .filter((Optional<Pair<Tid, DataType>> pr) -> pr.isPresent())
            .map(Optional::get)
            .collect(Collectors.toMap((var pr) -> pr.first, (var pr) -> pr.second));

    return new Types(tid_mapping_memo);
  }

  public static TypeLibrary parseFromInputStream(
      InputStream target,
      Map<String, DataType> type_constants,
      DataType unknownType,
      DataTypeManager dtm)
      throws IOException {
    var mapping = CTypeMapping.parseFrom(target);
    return new TypeLibrary(mapping, type_constants, unknownType, dtm);
  }
}
