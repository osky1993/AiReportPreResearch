  通过输入国库代码和所属年月等条件，查询进口环节税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；进口环节税子科目本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；出口业务退增值税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；出口业务退增值税科目本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；
  以查询国库代码为1005160000，所属年月为202603查询条件为例，
      进口环节税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010102','1010202', '101170101') 的结果
      进口环节税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010102','1010202', '101170101') 的结果
      进口环节税2月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010102','1010202', '101170101') 的结果
     依次查询进口环节税子科目为：1010102、1010202、101170101的本期执行金额、年累计金额、同比增减情况，上个月年累计金额及同比增减情况

  出口业务退增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010103') 的结果
      出口业务退增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010103') 的结果
      出口业务退增值税2月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010103') 的结果
     查询出口业务退增值税子科目：
    select distinct subject_code from treasury_income_detail  where subject_code  like '1010103%' and subject_code<>'1010103' stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')
    然后把每个子科目带入以上查询，查询出每个子科目的本期执行金额、本期执行年累计金额、本期执行同比增减情况、本期执行年累计同比增减情况，上个月年累计金额及同比增减情况。
 
 如果treasury_level为'3'，需要把这个国库代码作为上级国库代码，查询所有子国库代码，select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000'
    然后把查询出来的国库代码再带入查询所有子国库代码：select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')
    
    如果是只统计该月所在时间范围的数据，例如统计202602就只统计202602的数据，如果统计202607就只统计202607的数据
  


      本期同比增减额 = 本期金额 − 上年同期本期金额
      本期同比增减幅度 = 本期同比增减额 ÷ 上年同期本期金额（百分比，上年基数为 0 时标注无同比）
      累计同比增减额 = 年累计金额 − 上年同期累计金额
      累计同比增减幅度 = 累计同比增减额 ÷ 上年同期累计金额（百分比，上年基数为 0 时标注无同比）

      计算同比、占比等情况麻烦按照小数点后面8位汇总后计算
    

    
