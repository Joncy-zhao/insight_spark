const hasAnyToken = (text, tokens) => tokens.some(token => text.includes(token))

const hasAlertDomain = (text) =>
  /预警|告警|报警|警报|alert|warning|alarm/i.test(text)

const hasExplicitReference = (text) =>
  /(?:(?:预警|告警|报警|警报)?(?:规则|事件)|报警|告警|警报|rule|event|alarm)\s*(?:#|＃|id|ID|编号|为|是|:|：)?\s*\d+/i.test(text) ||
  /\d+\s*(?:号)?\s*(?:(?:预警|告警|报警|警报)?(?:规则|事件)|报警|告警|警报|rule|event|alarm)/i.test(text) ||
  /(?:#|＃|id|ID|编号)\s*\d+/i.test(text) ||
  /(这个|这条|该|当前|刚才|刚刚|上一条|最近一条|本条|它|其)/.test(text)

const isCreateAlertRuleQuestion = (text) =>
  /(创建|新建|新增|添加|生成|建一个|建一条).*(预警|告警|报警|警报|规则)/.test(text) ||
  /(提醒我|通知我|发邮件|发钉钉)/.test(text) ||
  /(如果|若|当).*(低于|高于|超过|跌破|异常).*(预警|告警|报警|提醒|通知)/.test(text) ||
  /设置(一个|一条)?.*(预警|告警|报警|警报)/.test(text)

export const isAlertOperationQuestion = (text = '') => {
  const content = String(text || '').trim()
  if (!content) return false

  const alertDomain = hasAlertDomain(content)
  const explicitReference = hasExplicitReference(content)
  const createRule = isCreateAlertRuleQuestion(content)
  const hasRuleTarget = hasAnyToken(content, [
    '预警规则',
    '告警规则',
    '报警规则',
    '警报规则',
    '规则',
    '阈值',
    '检测周期',
    '通知渠道',
    '推送渠道',
    '渠道',
    'rule'
  ])
  const hasRuleField = hasAnyToken(content, [
    '阈值',
    '检测周期',
    '通知渠道',
    '推送渠道',
    '渠道',
    '指标',
    '时间字段',
    '过滤条件',
    '条件'
  ])
  const hasRuleToggleAction = hasAnyToken(content, [
    '停用',
    '禁用',
    '暂停',
    '启用',
    '开启',
    '重开',
    '重新开启',
    '恢复',
    '恢复启用',
    '关闭',
    '删除',
    '删掉',
    '移除',
    '关闭规则'
  ])
  const hasRuleEditAction = hasAnyToken(content, [
    '修改',
    '调整',
    '改成',
    '改为',
    '更新',
    '设置',
    '设为',
    '编辑',
    '改一下',
    '改到',
    '调到'
  ])
  const hasRuleDetectAction = hasAnyToken(content, [
    '检测',
    '手动检测',
    '立即检测',
    '执行检测',
    '跑一下',
    '跑一次',
    '触发检测',
    '重新检测'
  ])
  const hasRuleDetectExecutionAction = hasRuleDetectAction && (
    !content.includes('检测周期') ||
    hasAnyToken(content, ['手动检测', '立即检测', '执行检测', '跑一下', '跑一次', '触发检测', '重新检测']) ||
    /^检测/.test(content)
  )

  if ((alertDomain || hasRuleField || (hasRuleDetectExecutionAction && explicitReference)) && hasRuleTarget) {
    if (hasRuleToggleAction && (!createRule || explicitReference)) {
      return true
    }
    if (hasRuleEditAction && (explicitReference || (hasRuleField && !createRule))) {
      return true
    }
    if (hasRuleDetectExecutionAction && explicitReference && !hasRuleEditAction) {
      return true
    }
  }

  const hasEventTarget = hasAnyToken(content, [
    '预警事件',
    '告警事件',
    '报警事件',
    '这个报警',
    '这条报警',
    '该报警',
    '报警',
    '告警',
    '警报',
    '事件',
    'event',
    'alarm'
  ])
  const hasEventAction = hasAnyToken(content, [
    '确认',
    '认领',
    'ack',
    'ACK',
    '关闭',
    '处理完成',
    '已处理',
    '解决',
    '重开',
    '重新打开',
    '重新开启',
    '恢复待处理',
    '恢复为待处理',
    '恢复处理'
  ])
  if (hasEventTarget && hasEventAction && (!createRule || explicitReference)) {
    return true
  }

  const hasEventQuery = hasAnyToken(content, [
    '最近',
    '哪些',
    '列表',
    '记录',
    '已触发',
    '触发过',
    '查看',
    '查询',
    '看一下',
    '详情',
    '原因',
    '解释',
    '为什么',
    '为何触发',
    '怎么触发',
    '快照'
  ])
  return alertDomain && hasEventQuery && !hasRuleTarget && !createRule
}
