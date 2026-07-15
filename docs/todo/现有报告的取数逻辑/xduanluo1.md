  通过输入国库代码和所属年月条件，查询全口径国库收入年累计金额及同比增减情况、中央级国库收入年累计金额及同比增减情况、省级国库收入年累计金额及同比增减情况、市级国库收入年累计金额及同比增减情况、地方级国库收入年累计金额及同比增减情况、库存余额及排名情况
  以查询国库代码为1005160000，所属年月为202603查询条件为例，其中全口径国库收入本期执行金额为
    全口径国库收入年累计金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101', 'T010201', 'T010401', 'T010601')的结果
    中央级国库收入年累计金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN ( '1')
    省级国库收入年累计金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN ( '2')
    市级国库收入年累计金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN ( '3')
    地方级国库收入年累计金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN  ( '4','5')
    
    国库库存余额为select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and stat_date='2026-03-31' 

    全市排名为
    首先找到所属上级国库代码：select treasury_code from treasury_basic_info where   admin_treasury_code= (select admin_treasury_code from treasury_basic_info where treasury_code ='1005160000' ) and treasury_level = (select treasury_level from treasury_basic_info where treasury_code ='1005160000')
    依次查询上面每个国库的库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx')  and stat_date='2026-03-31' 
    xxx代表上面查询出来的每个国库代码，然后再和1005160000国库比较库存余额的排名
    
    

    如果是只统计该月所在时间范围的数据，例如统计202602就只统计202602的数据，如果统计202607就只统计202607的数据，库存余额也只统计2026-03-31月末那天的数据

    查询条件为全口径则不需要带budget_level的条件，查询地方级则需要带budget_level的条件
其中budget_level的条件会动态变化，要根据查询国库代码在treasury_basic_info表中查询treasury_level的值
    如果treasury_level为'3'，则budget_level的条件为'3'、'4'、'5' 
    如果treasury_level为'4'，则budget_level的条件为'4'和'5'
    如果treasury_level为'5'，则budget_level的条件为'5'

    如果treasury_level为'3'，需要把这个国库代码作为上级国库代码，查询所有子国库代码，select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000'
    然后把查询出来的国库代码再带入查询所有子国库代码：select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')



    计算同比、占比等情况麻烦按照小数点后面8位汇总后计算