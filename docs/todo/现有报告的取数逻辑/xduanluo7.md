  通过输入国库代码和所属年月、统计口径（1表示全口径，2表示地方级）等条件，查询国有资本经营预算收入本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况，各个子科目（1030601、1030602、1030603）本期金额、年累计金额及同比增减情况；社会保险收入本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况，各个子科目本期金额、年累计金额及同比增减情况；
  以查询国库代码为1005160000，所属年月为202603查询条件为例，
     国有资本经营预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010601') and budget_level IN ( '4', '5')的结果
    国有资本经营预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010601') and budget_level IN ( '4', '5')的结果

    利润收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060103', '103060104','103060105','103060106','103060107','103060108','103060109','103060112', '103060113', '103060114', '103060115', '103060116', '103060117', '103060118', '103060119', '103060120', '103060121', '103060122', '103060123', '103060124', '
103060125', '103060126', '103060127', '103060128', '103060129', '103060130', '103060131', '103060132', '103060133', '103060134', '103060198') and budget_level IN ( '4', '5')的结果
    利润收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060103', '103060104','103060105','103060106','103060107','103060108','103060109','103060112', '103060113', '103060114', '103060115', '103060116', '103060117', '103060118', '103060119', '103060120', '103060121', '103060122', '103060123', '103060124', '
103060125', '103060126', '103060127', '103060128', '103060129', '103060130', '103060131', '103060132', '103060133', '103060134', '103060198') and budget_level IN ( '4', '5')的结果

   
    股息红利收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060202', '103060203','103060204','103060298') and budget_level IN ( '4', '5')的结果
    股息红利收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060202', '103060203','103060204','103060298') and budget_level IN ( '4', '5')的结果



    产权转让收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060301', '103060304','103060305','103060307','103060398') and budget_level IN ( '4', '5')的结果
    产权转让收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('103060301', '103060304','103060305','103060307','103060398') and budget_level IN ( '4', '5')的结果

    社会保险基金预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010401') and budget_level IN ( '4', '5')的结果
    社会保险基金预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010401') and budget_level IN ( '4', '5')的结果
    查询社会保险基金预算收入子科目：
    select distinct subject_code from treasury_income_detail  where subject_code  like '102%' and subject_code<>'102' stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')
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

    
