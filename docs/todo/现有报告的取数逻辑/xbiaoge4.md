通过输入国库代码和所属年月日日期（格式为yyyy-MM-dd）条件查询，统计如下某个国库的全市合计库存余额，同比去年增减情况，较上个月环比增减情况（一般是与上个月最后一天对比），日均库存余额及同比增减情况
市级库存余额，同比去年同同一个月增减情况，较年初环比增减情况（一般是与上个月最后一天对比），日均库存余额及同比增减情况
乡镇级库存余额，同比去年同同一个月增减情况，较年初环比增减情况（一般是与上个月最后一天对比），日均库存余额及同比增减情况
各个乡镇区库存余额，同比去年同同一个月增减情况，较年初环比增减情况（一般是与上个月最后一天对比），日均库存余额及同比增减情况

 例如输入：国库代码1005160000 日期2025-03-31
全市合计库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where county_treasury_code='1005160000'  and stat_date='2025-03-31'
select sum(balance)/31 from treasury_balance_journal where county_treasury_code='1005160000' and  stat_date between '2025-03-01' and '2025-03-31'
市级库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where county_treasury_code='1005160000' and treasury_code='1005160000' and stat_date='2025-03-31'
select sum(balance) from treasury_balance_journal where county_treasury_code='1005160000' and treasury_code='1005160000' and stat_date between '2025-03-01' and '2025-03-31'
乡镇库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where county_treasury_code='1005160000' and treasury_code<>'1005160000' and stat_date='2025-03-31'
select sum(balance)/31 from treasury_balance_journal where county_treasury_code='1005160000' and treasury_code<>'1005160000' and stat_date='2025-03-31'
各个乡镇区库库存余额、日均库存余额分別为：select treasury_short_name,balance from treasury_balance_journal  where county_treasury_code='1005160000' and treasury_code<>'1005160000' and stat_date='2026-03-31' order by treasury_code
select treasury_short_name,sum(balance)/31 from treasury_balance_journal  where county_treasury_code='1005160000' and treasury_code<>'1005160000' and stat_date between '2026-03-01' and '2026-03-31'  group by treasury_short_name order by treasury_code


特殊情况，如果输入国库代码1005000000 则特殊一点，
例如输入日期为2025-03-31
全市合计库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')  and stat_date='2025-03-31' 
select sum(balance)/31 from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')  and stat_date between '2025-03-01' and '2025-03-31'
市级库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where   treasury_code ='1005000000' and stat_date='2025-03-31'
select sum(balance)/31 from treasury_balance_journal where   treasury_code ='1005000000' and stat_date between '2025-03-01' and '2025-03-31'
区县库存余额、日均库存余额分別为select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000')  and stat_date='2025-03-31'  
select sum(balance)/31 from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000')  and stat_date between '2025-03-01' and '2025-03-31'

则查询各个区县国库的库存余额、日均库存余额分別为：
select treasury_short_name,balances,sum(balance)/31 from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000')  and stat_date='2025-03-31' 
select treasury_short_name,sum(balance)/31 from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000')  and stat_date between '2025-03-01' and '2025-03-31'  group by treasury_short_name order by treasury_code



 如果treasury_level为'3'，需要把这个国库代码作为上级国库代码，查询所有子国库代码，select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000'
    然后把查询出来的国库代码再带入查询所有子国库代码：select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='1005000000' or admin_treasury_code ='1005000000')


        计算同比、占比等情况麻烦按照小数点后面8位汇总后计算












