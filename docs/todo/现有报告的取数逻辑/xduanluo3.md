  通过输入国库代码和所属年月、统计口径（1表示全口径，2表示地方级）等条件，查询全市税收年累计金额和同比增减情况（即全市平均）、收入税收收入本月之前所有金额、年累计金额和同比增减情况、税收收入本期金额占一般公共预算收入的比重及上年同期税收比重（其中比重等于地方级税收收入累计金额除以地方一般公共预算收入累计金额，如果是全口径，就是等于全口径税收收入累计金额除以全口径一般公共预算收入累计金额），上个月年累计金额及同比增减情况；增值税本期金额、年累计金额和同比增减情况；国内增值税本期金额、年累计金额及同比增减情况；企业所得税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；个人所得税本期金额、年累计金额及同比增减情况；城市维护建设税本期金额、年累计金额及同比增减情况；房产税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；城镇土地使用税本期金额、年累计金额及同比增减情况；耕地占用税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；土地增值税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；契税本期金额、年累计金额及同比增减情况；印花税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；其他各项收入税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；非税收入本月之前所有金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；专项收入本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；行政事业性收费收入本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；国有资源（资产）有偿使用收入税本期金额、年累计金额及同比增减情况，上个月年累计金额及同比增减情况；
  
  以查询国库代码为1005160000，所属年月为202603查询条件为例，其中全口径国库收入本期执行金额为
    全市税收收入本期执行金额为
    首先查询出本国库上一级国库代码为：select admin_treasury_code from treasury_basic_info where treasury_code ='1005160000' 
    然后把上一级国库代码也加入查询条件（xxx用这个国库代码替换），计算本期全市税收收入本期执行金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (
        select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx')) and subject_code in ('101') and budget_level IN ('4', '5') 
        计算上年同期全市税收收入本期执行金额为
    select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202503' and treasury_code in  (
        select treasury_code from treasury_basic_info where treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx') or admin_treasury_code in (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx')) and subject_code in ('101') and budget_level IN ('4', '5') 

    税收收入1月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    税收收入1月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果

    税收收入2月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    税收收入2月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果

    税收收入3月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    税收收入3月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('101') and budget_level IN ( '4', '5')的结果

      一般公共预算收入3月份执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果
      一般公共预算收入3月份执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('T010101') and budget_level IN ( '4', '5')的结果

    增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10101') and budget_level IN ( '4', '5')的结果
    增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10101') and budget_level IN ( '4', '5')的结果
  
    国内增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果
    国内增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果

    企业所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000')  and subject_code in ('10104') and budget_level IN ( '4', '5')的结果
    企业所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10104') and budget_level IN ( '4', '5')的结果

    个人所得税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10106') and budget_level IN ( '4', '5')的结果
    个人所得税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10106') and budget_level IN ( '4', '5')的结果

     城市维护建设税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10109') and budget_level IN ( '4', '5')的结果
    城市维护建设税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10109') and budget_level IN ( '4', '5')的结果

    房产税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10110') and budget_level IN ( '4', '5')的结果
    房产税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10110') and budget_level IN ( '4', '5')的结果

     城镇土地使用税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10112') and budget_level IN ( '4', '5')的结果
    城镇土地使用税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10112') and budget_level IN ( '4', '5')的结果

     耕地占用税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10118') and budget_level IN ( '4', '5')的结果
    耕地占用税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10118') and budget_level IN ( '4', '5')的结果

     土地增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10113') and budget_level IN ( '4', '5')的结果
     土地增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10113') and budget_level IN ( '4', '5')的结果

    契税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10119') and budget_level IN ( '4', '5')的结果
     契税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10119') and budget_level IN ( '4', '5')的结果

    印花税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10111') and budget_level IN ( '4', '5')的结果
    印花税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10111') and budget_level IN ( '4', '5')的结果

    其他各项税收本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code ='101' and budget_level IN ( '4', '5')的结果
    减去select SUM(current_period_amount) from treasury_income_detail
    where stat_date between '202601' and '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101','10104','10106','10109','10110','10112','10118','10113','10119','10111') and budget_level IN ( '4', '5')的结果
    其他各项税收本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code = '101' and budget_level IN ( '4', '5')的结果
    减去select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10101','10104','10106','10109','10110','10112','10118','10113','10119','10111') and budget_level IN ( '4', '5')的结果

  非税收入1月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入1月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date ='202601' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

非税收入2月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入1月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date ='202602' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

    非税收入3月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入1月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

      非税收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

     专项收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10302') and budget_level IN ( '4', '5')的结果
    专项收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10302') and budget_level IN ( '4', '5')的结果

     专项收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10302') and budget_level IN ( '4', '5')的结果
    专项收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10302') and budget_level IN ( '4', '5')的结果

      行政事业性收费收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10304') and budget_level IN ( '4', '5')的结果
    行政事业性收费收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10304') and budget_level IN ( '4', '5')的结果

      罚没收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10305') and budget_level IN ( '4', '5')的结果
      罚没收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10305') and budget_level IN ( '4', '5')的结果

       国有资源（资产）有偿使用收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10307') and budget_level IN ( '4', '5')的结果
      国有资源（资产）有偿使用收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10307') and budget_level IN ( '4', '5')的结果

    同级国库同期税收收入的金额及同比增减情况查询流程：
    首先找到所属同一级的国库代码：select treasury_code from treasury_basic_info where   admin_treasury_code= (select admin_treasury_code from treasury_basic_info where treasury_code ='1005160000' ) and treasury_level = (select treasury_level from treasury_basic_info where treasury_code ='1005160000')
    依次查询上面每个国库的税收收入3月份执行金额：
    税收收入3月执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    税收收入3月执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='xxx' or admin_treasury_code ='xxx') and subject_code in ('101') and budget_level IN ( '4', '5')的结果
    xxx代表上面查询出来的每个国库代码，然后再和1005160000国库比较税收收入累计增速的排名
   

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