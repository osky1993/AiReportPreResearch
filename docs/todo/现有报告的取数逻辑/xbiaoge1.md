  通过输入国库代码和所属年月条件，查询全口径国库收入本期金额、年累计金额及同比增减情况；地方级国库收入本期金额、年累计金额及同比增减情况；一般公共预算收入本期金额、年累计金额及同比增减情况；税收收入本期金额、年累计金额和同比增减情况；增值税本期金额、年累计金额和同比增减情况；国内增值税本期金额、年累计金额及同比增减情况；企业所得税本期金额、年累计金额及同比增减情况；个人所得税本期金额、年累计金额及同比增减情况；城市维护建设税本期金额、年累计金额及同比增减情况；房产税本期金额、年累计金额及同比增减情况；城镇土地使用税本期金额、年累计金额及同比增减情况；耕地占用税本期金额、年累计金额及同比增减情况；土地增值税本期金额、年累计金额及同比增减情况；契税本期金额、年累计金额及同比增减情况；印花税本期金额、年累计金额及同比增减情况；其他各项收入税本期金额、年累计金额及同比增减情况；非税收入本期金额、年累计金额及同比增减情况；专项收入本期金额、年累计金额及同比增减情况；行政事业性收费收入本期金额、年累计金额及同比增减情况；国有资源（资产）有偿使用收入税本期金额、年累计金额及同比增减情况；政府性基金预算收入本期金额、年累计金额及同比增减情况；国有土地使用出让收入本期金额、年累计金额及同比增减情况；国有资本经营预算收入本期金额、年累计金额及同比增减情况；利润收入本期金额、年累计金额及同比增减情况；股息红利收入本期金额、年累计金额及同比增减情况；产权转让收入本期金额、年累计金额及同比增减情况；社会保险基金预算收入本期金额、年累计金额及同比增减情况；期末库存各个级次本期余额、年累计余额及同比增减情况；
  以查询国库代码为1005160000，所属年月为202603查询条件为例，其中全口径国库收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101', 'T010201', 'T010401', 'T010601')的结果
    全口径国库收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101', 'T010201', 'T010401', 'T010601')的结果

    地方级国库收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN ( '4', '5')的结果

    地方级国库收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101', 'T010201', 'T010401', 'T010601') and budget_level IN ( '4', '5')的结果

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
  
    国内增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果
    国内增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('1010101') and budget_level IN ( '4', '5')的结果

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

     城市维护建设税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10109') and budget_level IN ( '4', '5')的结果
    城市维护建设税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10109') and budget_level IN ( '4', '5')的结果

    房产税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110') and budget_level IN ( '4', '5')的结果
    房产税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10110') and budget_level IN ( '4', '5')的结果

     城镇土地使用税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10112') and budget_level IN ( '4', '5')的结果
    城镇土地使用税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10112') and budget_level IN ( '4', '5')的结果

     耕地占用税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10118') and budget_level IN ( '4', '5')的结果
    耕地占用税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10118') and budget_level IN ( '4', '5')的结果

     土地增值税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10113') and budget_level IN ( '4', '5')的结果
     土地增值税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10113') and budget_level IN ( '4', '5')的结果

    契税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10119') and budget_level IN ( '4', '5')的结果
     契税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10119') and budget_level IN ( '4', '5')的结果

    印花税本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10111') and budget_level IN ( '4', '5')的结果
    印花税本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10111') and budget_level IN ( '4', '5')的结果

    其他各项税收本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code ='101' and budget_level IN ( '4', '5')的结果
    减去select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10101','10104','10106','10109','10110','10112','10118','10113','10119','10111') and budget_level IN ( '4', '5')的结果
    其他各项税收本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code ='101' and budget_level IN ( '4', '5')的结果
    减去select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') and subject_code in ('10101','10104','10106','10109','10110','10112','10118','10113','10119','10111') and budget_level IN ( '4', '5')的结果

  非税收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

      非税收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010101') and budget_level IN ( '4', '5')
   减去非税收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('101') and budget_level IN ( '4', '5')的结果

     专项收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10302') and budget_level IN ( '4', '5')的结果
    专项收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10302') and budget_level IN ( '4', '5')的结果

     专项收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10302') and budget_level IN ( '4', '5')的结果
    专项收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10302') and budget_level IN ( '4', '5')的结果

      行政事业性收费收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10304') and budget_level IN ( '4', '5')的结果
    行政事业性收费收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10304') and budget_level IN ( '4', '5')的结果

      罚没收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10305') and budget_level IN ( '4', '5')的结果
      罚没收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10305') and budget_level IN ( '4', '5')的结果

       国有资源（资产）有偿使用收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10307') and budget_level IN ( '4', '5')的结果
      国有资源（资产）有偿使用收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('10307') and budget_level IN ( '4', '5')的结果


    政府性基金预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010201') and budget_level IN ( '4', '5')的结果
    政府性基金预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010201') and budget_level IN ( '4', '5')的结果

    国有土地使用权出让收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('1030148') and budget_level IN ( '4', '5')的结果
    国有土地使用权出让收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('1030148') and budget_level IN ( '4', '5')的结果

    

    国有资本经营预算收入本期执行金额为select SUM(current_period_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010601') and budget_level IN ( '4', '5')的结果
    国有资本经营预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date ='202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010601') and budget_level IN ( '4', '5')的结果

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
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010401') and budget_level IN ( '4', '5')的结果
    社会保险基金预算收入本期执行年累计金额为select SUM(year_to_date_amount) from treasury_income_detail
    where stat_date = '202603' and treasury_code in  (select treasury_code from treasury_basic_info where treasury_code ='1005160000' or admin_treasury_code ='1005160000') 
    and subject_code in ('T010401') and budget_level IN ( '4', '5')的结果

    库存查询要根据查询国库代码在treasury_basic_info表中查询treasury_level的值，如果是treasury_level为'3'，则分别查询市级、区县级、乡镇级的库存余额
    市级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code ='1005000000' and stat_date='2026-03-31' 
    区县级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000' and  treasury_code='4')  and stat_date='2026-03-31' 
    乡镇级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000'  and  treasury_code='5')  and stat_date='2026-03-31' 
    如果是treasury_level为'4'，则分别查询区县级、乡镇级的库存余额
    区县级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000' and  treasury_code='4')  and stat_date='2026-03-31' 
    乡镇级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000'  and  treasury_code='5')  and stat_date='2026-03-31' 
    如果是treasury_level为'4'，则分别查询乡镇级的库存余额
    乡镇级库存余额：select sum(balance) from treasury_balance_journal where   treasury_code in  (select treasury_code from treasury_basic_info where  admin_treasury_code ='1005000000'  and  treasury_code='5')  and stat_date='2026-03-31' 


    如果是统计本期执行金额时，只统计该月所在月时间范围的数据，例如统计202602就只统计202602的数据，如果统计202607就只统计202607的数据

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