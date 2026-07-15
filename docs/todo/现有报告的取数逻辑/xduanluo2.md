  通过输入国库代码和所属年月、统计口径（1表示全口径，2表示地方级）等条件，查询一般公共预算收入本月之前所有月份的金额及同比增减情况，一般公共预算收入年累计金额及同比增减情况，同级国库同期一般共公共预算收入的金额及同比增减情况，一般公共预算收入年累计金额及同比增减情况、预算收入累计增速排名。
  
  以查询国库代码为1005160000，所属年月为202603查询条件为例，其中全口径国库收入本期执行金额为
     一般公共预算收入1月份执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入1月份执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果

    一般公共预算收入2月份执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入2月份执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果

     一般公共预算收入3月份执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入3月份执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果

    同级国库同期一般共公共预算收入的金额及同比增减情况查询流程：
    首先找到所属同一级的国库代码：select treasury_code from treasury_basic_info where   admin_treasury_code= (select admin_treasury_code from treasury_basic_info where treasury_code ='1005160000' ) and treasury_level = (select treasury_level from treasury_basic_info where treasury_code ='1005160000')
    依次查询上面每个国库的一般公共预算收入3月份执行金额：
    一般公共预算收入3月份执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入3月份执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
    xxx代表上面查询出来的每个国库代码，然后再和1005160000国库比较累计同比增减幅度的排名
   

    如果是只统计该月所在时间范围的数据，例如统计202602就只统计202602的数据，如果统计202607就只统计202607的数据
查询条件为全口径则不需要带budget_level的条件，查询地方级则需要带budget_level的条件
其中budget_level的条件会动态变化，要根据查询国库代码在treasury_basic_info表中查询treasury_level的值
    如果treasury_level为'3'，则budget_level的条件为'3'、'4'、'5' 
    如果treasury_level为'4'，则budget_level的条件为'4'和'5'
    如果treasury_level为'5'，则budget_level的条件为'5'

    如果treasury_level为'3'，需要把这个国库代码作为上级国库代码，查询所有子国库代码，select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000'
    然后把查询出来的国库代码再带入查询所有子国库代码：select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')
    


本期同比增减额 = 本期金额 − 上年同期本期金额
本期同比增减幅度 = 本期同比增减额 ÷ 上年同期本期金额（百分比，上年基数为 0 时标注无同比）
累计同比增减额 = 年累计金额 − 上年同期累计金额
累计同比增减幅度 = 累计同比增减额 ÷ 上年同期累计金额（百分比，上年基数为 0 时标注无同比）


计算同比、占比等情况麻烦按照小数点后面8位汇总后计算