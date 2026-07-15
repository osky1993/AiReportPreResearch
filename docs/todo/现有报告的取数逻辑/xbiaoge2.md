  通过输入国库代码和所属年月条件，查询全口径、区县级、乡镇级的一般公共预算收入本期金额、年累计金额及同比增减情况；税收收入本期金额、年累计金额和同比增减情况以及占一般公共预算收入的比重（其中比重等于地方级税收收入除以一般公共预算收入）；增值税本期金额、年累计金额和同比增减情况；企业所得税本期金额、年累计金额及同比增减情况；个人所得税本期金额、年累计金额及同比增减情况；房产五税本期金额、年累计金额及同比增减情况；以及各个子国库的这些税种明细；
  
   以查询国库代码为1005160000，所属年月为202603查询条件为例，统计全辖所有子国库的本期执行金额、年累计金额及同比增减情况；
   以下全辖汇总结果：

      
     
     全口径为：
    一般公共预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
    
    税收收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    税收收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

    增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '4', '5')的结果
    增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '4', '5')的结果
  

    企业所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '4', '5')的结果
    企业所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '4', '5')的结果

    个人所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '4', '5')的结果
    个人所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '4', '5')的结果

    房产五税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118') and budget_level IN ( '4', '5')的结果
    房产五税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118')and budget_level IN ( '4', '5')的结果

     区县级为：
    一般公共预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4')的结果
      一般公共预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4')的结果
    
    税收收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4')的结果
    税收收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4')的结果

    增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '4')的结果
    增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '4')的结果
  

    企业所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '4')的结果
    企业所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '4')的结果

    个人所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '4')的结果
    个人所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '4')的结果

    房产五税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118') and budget_level IN ( '4')的结果
    房产五税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118')and budget_level IN ( '4')的结果
   

     乡镇级为：
    一般公共预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '5')的结果
      一般公共预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '5')的结果
    
    税收收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '5')的结果
    税收收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '5')的结果

    增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '5')的结果
    增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101') and budget_level IN ( '5')的结果
  

    企业所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '5')的结果
    企业所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10104') and budget_level IN ( '5')的结果

    个人所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '5')的结果
    个人所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10106') and budget_level IN ( '5')的结果

    房产五税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118') and budget_level IN ( '5')的结果
    房产五税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110'、'10112'、'10113'、'10119'、'10118')and budget_level IN ( '5')的结果


    各个子国库的地方级本期执行金额、年累计金额及同比增减情况：
    就是根据sql查询select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000'，获取各个子国库的国库代码，把条件带入
    以上sql的treasury_code字段中就能查询出各个子国库地方级的本期执行金额、年累计金额及同比增减情况



    如果是统计本期执行金额时，只统计该月所在月时间范围的数据，例如统计202602就只统计202602的数据，如果统计202607就只统计202607的数据

    查询各个子国库时其中budget_level的条件会动态变化，要根据查询国库代码在treasury_basic_info表中查询treasury_level的值
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