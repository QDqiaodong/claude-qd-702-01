<template>
  <el-table :data="rows" size="small" border class="record-read-table">
    <el-table-column label="#" type="index" width="40" />
    <el-table-column prop="calibrationPoint" label="校准点" min-width="110" />
    <el-table-column prop="standardValue" label="标准值" min-width="100" />
    <el-table-column prop="indicatedValue" label="示值" min-width="100" />
    <el-table-column prop="errorValue" label="误差" min-width="90" />
    <el-table-column prop="unit" label="单位" width="72" />
    <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
  </el-table>
</template>

<script setup>
defineProps({ rows: { type: Array, default: () => [] } })
</script>

<style scoped>
.record-read-table {
  width: 100%;
  margin: 4px 0 8px;
}
</style>
