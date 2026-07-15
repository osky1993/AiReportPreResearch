  通过输入国库代码和所属年月、统计口径（1表示全口径，2表示地方级）等条件，查询收入税收收入本期执行金额、年累计金额和同比增减情况；国内增值税本期金额、年累计金额及同比增减情况，上个月金额、年累计金额及同比增减情况，占税收收入比重情况及上年同期比重情况（如果条件是地方级则其中比重等于地方级国内增值税累计金额除以地方级税收收入累计金额，如果是全口径，就是等于全口径国内增值税累计金额除以全口径税收收入累计金额）；
  国内增值税子科目本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；企业所得税本期金额、年累计金额及同比增减情况，上个月金额、年累计金额及同比增减情况，企业所得税子科目本期金额、年累计金额及同比增减情况，占税收收入比重情况及上年同期比重情况（如果条件是地方级，其中比重等于地方级企业所得税累计金额除以地方税收收入累计金额，如果是全口径，就是等于全口径企业所得税累计金额除以全口径税收收入累计金额）；
  个人所得税本期金额、年累计金额及同比增减情况，上个月金额、年累计金额及同比增减情况，个人所得税子科目本期金额、年累计金额及同比增减情况，上个月金额、年累计金额及同比增减情况；
  
  以查询国库代码为1005160000，所属年月为202603查询条件为例，其中

    地方级税收收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    地方级税收收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果

      国内增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果
    国内增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果
    国内增值税2月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果
     查询国内增值税子科目：
    select distinct subject_code from treasury_income_detail  where subject_code  like '1010101%' and subject_code<>'1010101' stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')
    然后把每个子科目带入以上查询，查询出每个子科目的本期执行金额、本期执行年累计金额、本期执行同比增减情况、本期执行年累计同比增减情况。

    企业所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('10104') and budget_level IN ( '4', '5')的结果
    企业所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10104') and budget_level IN ( '4', '5')的结果
    查询企业所得税子科目：
    select distinct subject_code from treasury_income_detail  where subject_code  like '10104%' and subject_code<>'10104' stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')
    然后把每个子科目带入以上查询，查询出每个子科目的本期执行金额、本期执行年累计金额、本期执行同比增减情况、本期执行年累计同比增减情况。

    个人所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10106') and budget_level IN ( '4', '5')的结果
    个人所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10106') and budget_level IN ( '4', '5')的结果
    查询个人所得税子科目：
    select distinct subject_code from treasury_income_detail  where subject_code  like '10106%' and subject_code<>'10106' stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')
    然后把每个子科目带入以上查询，查询出每个子科目的本期执行金额、本期执行年累计金额、本期执行同比增减情况、本期执行年累计同比增减情况。
    
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
    

    
