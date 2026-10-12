--liquibase formatted sql
--changeset orderms:002
INSERT INTO products(id,name,description,price,currency,available,created_at,updated_at,version) VALUES
 ('00000000-0000-4000-8000-000000000001','Wireless Mouse','Compact wireless mouse',25.00,'EUR',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
 ('00000000-0000-4000-8000-000000000002','Keyboard','Mechanical keyboard',75.50,'EUR',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
 ('00000000-0000-4000-8000-000000000003','Desk Mat','Unavailable demonstration product',10.00,'EUR',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);
